package bridge

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type tunUDPKey struct {
	srcIP string
	srcPort uint16
	dstIP string
	dstPort uint16
}

type tunUDPFlow struct {
	key        tunUDPKey
	control    net.Conn
	udpConn    *net.UDPConn
	lastActive atomic.Int64
	closed     atomic.Bool
	closeOnce  sync.Once
}

var tunUDPFlows sync.Map // map[*TunRouter]*sync.Map

func (r *TunRouter) handleGenericUDP(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) {
	if !r.running.Load() || len(payload) == 0 || len(payload) > 65507 {
		return
	}
	key := tunUDPKey{srcIP: srcIP.String(), srcPort: srcPort, dstIP: dstIP.String(), dstPort: dstPort}
	stateAny, _ := tunUDPFlows.LoadOrStore(r, &sync.Map{})
	flows := stateAny.(*sync.Map)
	flowAny, ok := flows.Load(key)
	if !ok {
		flow, err := r.newUDPFlow(key)
		if err != nil {
			return
		}
		actual, loaded := flows.LoadOrStore(key, flow)
		if loaded {
			flow.close()
			flow = actual.(*tunUDPFlow)
		} else {
			go r.readUDPFlow(flows, key, flow)
		}
		flowAny = flow
	}
	flow := flowAny.(*tunUDPFlow)
	if flow.closed.Load() {
		flows.Delete(key)
		return
	}
	flow.lastActive.Store(time.Now().UnixNano())
	if err := writeSocks5UDP(flow.udpConn, dstIP, dstPort, payload); err != nil {
		flow.close()
		flows.Delete(key)
	}
}

func (r *TunRouter) newUDPFlow(key tunUDPKey) (*tunUDPFlow, error) {
	control, err := net.DialTimeout("tcp", r.socksAddr, 5*time.Second)
	if err != nil {
		return nil, err
	}
	_ = control.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = control.Write([]byte{5, 1, 0}); err != nil {
		_ = control.Close()
		return nil, err
	}
	var authReply [2]byte
	if _, err = io.ReadFull(control, authReply[:]); err != nil || authReply[0] != 5 || authReply[1] != 0 {
		_ = control.Close()
		return nil, errors.New("SOCKS5 authentication negotiation failed")
	}
	req := []byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}
	if _, err = control.Write(req); err != nil {
		_ = control.Close()
		return nil, err
	}
	var replyHead [4]byte
	if _, err = io.ReadFull(control, replyHead[:]); err != nil || replyHead[0] != 5 || replyHead[1] != 0 {
		_ = control.Close()
		return nil, errors.New("SOCKS5 UDP associate failed")
	}
	relayHost, err := readSocks5ReplyAddr(control, replyHead[3])
	if err != nil {
		_ = control.Close()
		return nil, err
	}
	var portBuf [2]byte
	if _, err = io.ReadFull(control, portBuf[:]); err != nil {
		_ = control.Close()
		return nil, err
	}
	relayPort := binary.BigEndian.Uint16(portBuf[:])
	if relayPort == 0 {
		_ = control.Close()
		return nil, errors.New("SOCKS5 returned invalid UDP relay port")
	}
	_ = control.SetDeadline(time.Time{})
	if relayHost == "0.0.0.0" || relayHost == "::" {
		relayHost = "127.0.0.1"
	}
	relayIP := net.ParseIP(relayHost).To4()
	if relayIP == nil {
		_ = control.Close()
		return nil, fmt.Errorf("SOCKS5 UDP relay address is not IPv4: %s", relayHost)
	}
	udpConn, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: relayIP, Port: int(relayPort)})
	if err != nil {
		_ = control.Close()
		return nil, err
	}
	return &tunUDPFlow{key: key, control: control, udpConn: udpConn}, nil
}

func readSocks5ReplyAddr(r io.Reader, atyp byte) (string, error) {
	switch atyp {
	case 1:
		var b [4]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", err
		}
		return net.IP(b[:]).String(), nil
	case 3:
		var n [1]byte
		if _, err := io.ReadFull(r, n[:]); err != nil {
			return "", err
		}
		if n[0] == 0 {
			return "", errors.New("SOCKS5 returned empty UDP relay hostname")
		}
		b := make([]byte, n[0])
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		return string(b), nil
	case 4:
		var b [16]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", err
		}
		return net.IP(b[:]).String(), nil
	default:
		return "", errors.New("SOCKS5 returned invalid address type")
	}
}

func writeSocks5UDP(conn *net.UDPConn, dstIP net.IP, dstPort uint16, payload []byte) error {
	ip := dstIP.To4()
	if ip == nil {
		return errors.New("IPv6 UDP destinations are not supported by the current Android TUN path")
	}
	if len(payload) > 65507 {
		return errors.New("UDP payload too large")
	}
	datagram := make([]byte, 10+len(payload))
	datagram[3] = 1
	copy(datagram[4:8], ip)
	binary.BigEndian.PutUint16(datagram[8:10], dstPort)
	copy(datagram[10:], payload)
	_, err := conn.Write(datagram)
	return err
}

func (r *TunRouter) readUDPFlow(flows *sync.Map, key tunUDPKey, flow *tunUDPFlow) {
	defer func() {
		flows.Delete(key)
		flow.close()
	}()
	buf := make([]byte, 64*1024)
	for r.running.Load() && !flow.closed.Load() {
		if flow.lastActive.Load() > 0 && time.Since(time.Unix(0, flow.lastActive.Load())) > 90*time.Second {
			return
		}
		_ = flow.udpConn.SetReadDeadline(time.Now().Add(1 * time.Second))
		n, err := flow.udpConn.Read(buf)
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			return
		}
		flow.lastActive.Store(time.Now().UnixNano())
		srcIP, srcPort, payload, err := parseSocks5UDP(buf[:n])
		if err != nil || len(payload) == 0 {
			continue
		}
		tunDst := net.ParseIP(key.srcIP).To4()
		if tunDst == nil || r.getTunFile() == nil {
			continue
		}
		pkt := craftUDPPacket(srcIP, tunDst, srcPort, key.srcPort, payload)
		_, _ = r.writeTun(pkt)
	}
}

func parseSocks5UDP(packet []byte) (net.IP, uint16, []byte, error) {
	if len(packet) < 10 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 || packet[3] != 1 {
		return nil, 0, nil, errors.New("invalid SOCKS5 UDP response")
	}
	ip := net.IPv4(packet[4], packet[5], packet[6], packet[7]).To4()
	port := binary.BigEndian.Uint16(packet[8:10])
	return ip, port, append([]byte(nil), packet[10:]...), nil
}

func (f *tunUDPFlow) close() {
	f.closeOnce.Do(func() {
		f.closed.Store(true)
		if f.udpConn != nil {
			_ = f.udpConn.Close()
		}
		if f.control != nil {
			_ = f.control.Close()
		}
	})
}

func stopTunUDP(r *TunRouter) {
	if stateAny, ok := tunUDPFlows.LoadAndDelete(r); ok {
		stateAny.(*sync.Map).Range(func(key, value any) bool {
			value.(*tunUDPFlow).close()
			return true
		})
	}
}
