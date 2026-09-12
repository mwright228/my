package bridge

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/sys/unix"
)

const (
	tunIPv4Address = "172.19.0.1/30"
	tunIPv6Address = "fd19:2e58:34::1/126"
	tunMTU         = 1500
	udpMaxPayload  = 65507
	socksTimeout    = 5 * time.Second
)

var (
	activeRouter   *TunRouter
	activeRouterMu sync.Mutex
)

type TunRouter struct {
	tun       tun.Tun
	stack     tun.Stack
	cancel    context.CancelFunc
	socksAddr string
	dnsServer string
	running   atomic.Bool
}

// tunSOCKSHandler terminates TUN TCP/UDP flows using sing-tun's maintained IP
// stack and forwards the resulting streams/packets to the existing loopback
// SOCKS5 listener. This removes the application-owned TCP/IP state machine while
// preserving the proxy protocol implementations behind the local SOCKS boundary.
type tunSOCKSHandler struct {
	socksAddr string
}

func StartTunRouter(fd int, socksPort int) error {
	return StartTunRouterWithDNS(fd, socksPort, "1.1.1.1:53")
}

func StartTunRouterWithDNS(fd int, socksPort int, dnsServer string) error {
	activeRouterMu.Lock()
	defer activeRouterMu.Unlock()

	if activeRouter != nil && activeRouter.running.Load() {
		return errors.New("tun router is already active")
	}
	if fd < 0 {
		return errors.New("invalid tun file descriptor")
	}
	if socksPort <= 0 || socksPort > 65535 {
		return fmt.Errorf("invalid SOCKS5 port %d", socksPort)
	}

	dupFD, err := unix.Dup(fd)
	if err != nil {
		return fmt.Errorf("duplicate tun file descriptor: %w", err)
	}

	options := tun.Options{
		Name:             "mubx-tun",
		Inet4Address:     []netip.Prefix{netip.MustParsePrefix(tunIPv4Address)},
		Inet6Address:     []netip.Prefix{netip.MustParsePrefix(tunIPv6Address)},
		MTU:              tunMTU,
		AutoRoute:        false,
		StrictRoute:      false,
		FileDescriptor:   dupFD,
		Logger:           logger.NOP(),
	}
	device, err := tun.New(options)
	if err != nil {
		_ = unix.Close(dupFD)
		return fmt.Errorf("open Android TUN descriptor: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	handler := &tunSOCKSHandler{socksAddr: net.JoinHostPort("127.0.0.1", fmt.Sprintf("%d", socksPort))}
	stack, err := tun.NewStack("gvisor", tun.StackOptions{
		Context:                ctx,
		Tun:                    device,
		TunOptions:             options,
		UDPTimeout:             90,
		Handler:                handler,
		Logger:                 logger.NOP(),
		ForwarderBindInterface: false,
		IncludeAllNetworks:     false,
	})
	if err != nil {
		cancel()
		_ = device.Close()
		return fmt.Errorf("create sing-tun gVisor stack: %w", err)
	}
	if err := stack.Start(); err != nil {
		cancel()
		_ = stack.Close()
		_ = device.Close()
		return fmt.Errorf("start sing-tun gVisor stack: %w", err)
	}

	r := &TunRouter{
		tun:       device,
		stack:     stack,
		cancel:    cancel,
		socksAddr: handler.socksAddr,
		dnsServer: dnsServer,
	}
	r.running.Store(true)
	activeRouter = r
	LogMsg("ROUTER", fmt.Sprintf("sing-tun gVisor stack active (SOCKS: %s, MTU: %d, IPv4+IPv6)", r.socksAddr, tunMTU))
	return nil
}

func StopTunRouter() {
	activeRouterMu.Lock()
	defer activeRouterMu.Unlock()

	if activeRouter == nil || !activeRouter.running.Load() {
		return
	}
	r := activeRouter
	r.running.Store(false)
	if r.cancel != nil {
		r.cancel()
	}
	if r.stack != nil {
		_ = r.stack.Close()
	}
	if r.tun != nil {
		_ = r.tun.Close()
		r.tun = nil
	}
	activeRouter = nil
	LogMsg("ROUTER", "sing-tun gVisor stack stopped")
}

func (h *tunSOCKSHandler) NewError(_ context.Context, err error) {
	if err != nil {
		LogMsg("ROUTER", fmt.Sprintf("TUN stack flow error: %v", err))
	}
}

func (h *tunSOCKSHandler) NewConnection(ctx context.Context, conn net.Conn, metadata M.Metadata) error {
	if !metadata.Destination.IsValid() {
		return errors.New("TUN TCP destination is invalid")
	}
	ActiveConns.Add(1)
	defer ActiveConns.Add(-1)
	defer conn.Close()

	upstream, err := dialSocks5TCP(ctx, h.socksAddr, metadata.Destination)
	if err != nil {
		return fmt.Errorf("SOCKS5 TCP connect to %s: %w", metadata.Destination.String(), err)
	}
	defer upstream.Close()
	return proxyTCP(ctx, conn, upstream)
}

func (h *tunSOCKSHandler) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata M.Metadata) error {
	if !metadata.Source.IsValid() {
		return errors.New("TUN UDP source is invalid")
	}
	controlConn, udpConn, err := openSocks5UDP(ctx, h.socksAddr)
	if err != nil {
		return fmt.Errorf("open SOCKS5 UDP association: %w", err)
	}
	defer controlConn.Close()
	defer udpConn.Close()
	defer conn.Close()

	flowCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	errCh := make(chan error, 2)

	go func() {
		for {
			packet := buf.NewPacket()
			destination, err := conn.ReadPacket(packet)
			if err != nil {
				packet.Release()
				errCh <- err
				return
			}
			payload := append([]byte(nil), packet.Bytes()...)
			packet.Release()
			if len(payload) > udpMaxPayload {
				errCh <- errors.New("UDP payload too large")
				return
			}
			if err := writeSocks5UDPDatagram(udpConn, destination, payload); err != nil {
				errCh <- err
				return
			}
		}
	}()

	go func() {
		packetBuf := make([]byte, 65535)
		for {
			if err := udpConn.SetReadDeadline(time.Now().Add(1 * time.Second)); err != nil {
				errCh <- err
				return
			}
			n, err := udpConn.Read(packetBuf)
			if err != nil {
				if ne, ok := err.(net.Error); ok && ne.Timeout() {
					select {
					case <-flowCtx.Done():
						errCh <- flowCtx.Err()
						return
					default:
						continue
					}
				}
				errCh <- err
				return
			}
			payload, err := parseSocks5UDPDatagram(packetBuf[:n])
			if err != nil {
				continue
			}
			out := buf.As(append([]byte(nil), payload...))
			if err := conn.WritePacket(out, metadata.Source); err != nil {
				errCh <- err
				return
			}
		}
	}()

	select {
	case err := <-errCh:
		cancel()
		if err == context.Canceled || err == context.DeadlineExceeded {
			return nil
		}
		return err
	case <-ctx.Done():
		return nil
	}
}

func proxyTCP(ctx context.Context, client net.Conn, upstream net.Conn) error {
	result := make(chan error, 2)
	copyHalf := func(dst, src net.Conn) {
		_, err := io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
		result <- err
	}
	go copyHalf(upstream, client)
	go copyHalf(client, upstream)

	var firstErr error
	for range 2 {
		select {
		case err := <-result:
			if err != nil && firstErr == nil && !errors.Is(err, net.ErrClosed) && !errors.Is(err, io.EOF) {
				firstErr = err
			}
		case <-ctx.Done():
			_ = client.Close()
			_ = upstream.Close()
			return ctx.Err()
		}
	}
	return firstErr
}

func dialSocks5TCP(ctx context.Context, socksAddr string, destination M.Socksaddr) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: socksTimeout}
	conn, err := dialer.DialContext(ctx, "tcp", socksAddr)
	if err != nil {
		return nil, err
	}
	if err := conn.SetDeadline(time.Now().Add(socksTimeout)); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if err := writeAll(conn, []byte{5, 1, 0}); err != nil {
		_ = conn.Close()
		return nil, err
	}
	var reply [2]byte
	if _, err := io.ReadFull(conn, reply[:]); err != nil || reply[0] != 5 || reply[1] != 0 {
		_ = conn.Close()
		return nil, errors.New("SOCKS5 authentication negotiation failed")
	}
	request := []byte{5, 1, 0}
	address, err := socks5Address(destination)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	request = append(request, address...)
	if err := writeAll(conn, request); err != nil {
		_ = conn.Close()
		return nil, err
	}
	var head [4]byte
	if _, err := io.ReadFull(conn, head[:]); err != nil || head[0] != 5 || head[1] != 0 {
		_ = conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect rejected: reply=%d", head[1])
	}
	if _, _, err := readSocks5AddressAndPort(conn, head[3]); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if err := conn.SetDeadline(time.Time{}); err != nil {
		_ = conn.Close()
		return nil, err
	}
	return conn, nil
}

func openSocks5UDP(ctx context.Context, socksAddr string) (net.Conn, *net.UDPConn, error) {
	controlConn, err := (&net.Dialer{Timeout: socksTimeout}).DialContext(ctx, "tcp", socksAddr)
	if err != nil {
		return nil, nil, err
	}
	if err := controlConn.SetDeadline(time.Now().Add(socksTimeout)); err != nil {
		_ = controlConn.Close()
		return nil, nil, err
	}
	if err := writeAll(controlConn, []byte{5, 1, 0}); err != nil {
		_ = controlConn.Close()
		return nil, nil, err
	}
	var reply [2]byte
	if _, err := io.ReadFull(controlConn, reply[:]); err != nil || reply[0] != 5 || reply[1] != 0 {
		_ = controlConn.Close()
		return nil, nil, errors.New("SOCKS5 authentication negotiation failed")
	}
	request := []byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}
	if err := writeAll(controlConn, request); err != nil {
		_ = controlConn.Close()
		return nil, nil, err
	}
	var head [4]byte
	if _, err := io.ReadFull(controlConn, head[:]); err != nil || head[0] != 5 || head[1] != 0 {
		_ = controlConn.Close()
		return nil, nil, fmt.Errorf("SOCKS5 UDP associate rejected: reply=%d", head[1])
	}
	relayHost, relayPort, err := readSocks5AddressAndPort(controlConn, head[3])
	if err != nil {
		_ = controlConn.Close()
		return nil, nil, err
	}
	if relayHost == "0.0.0.0" {
		relayHost = "127.0.0.1"
	} else if relayHost == "::" {
		relayHost = "::1"
	}
	relayIP := net.ParseIP(relayHost)
	if relayIP == nil || relayPort == 0 {
		_ = controlConn.Close()
		return nil, nil, fmt.Errorf("invalid SOCKS5 UDP relay address %q:%d", relayHost, relayPort)
	}
	udpConn, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: relayIP, Port: int(relayPort)})
	if err != nil {
		_ = controlConn.Close()
		return nil, nil, err
	}
	if err := controlConn.SetDeadline(time.Time{}); err != nil {
		_ = udpConn.Close()
		_ = controlConn.Close()
		return nil, nil, err
	}
	return controlConn, udpConn, nil
}

func socks5Address(destination M.Socksaddr) ([]byte, error) {
	if !destination.IsValid() {
		return nil, errors.New("invalid SOCKS5 destination")
	}
	out := make([]byte, 0, 1+16+2)
	switch {
	case destination.IsIPv4():
		out = append(out, 1)
		out = append(out, destination.Addr.AsSlice()...)
	case destination.IsIPv6():
		out = append(out, 4)
		out = append(out, destination.Addr.AsSlice()...)
	case destination.IsFqdn():
		if destination.Fqdn == "" || len(destination.Fqdn) > 255 || strings.ContainsAny(destination.Fqdn, "\x00\r\n") {
			return nil, errors.New("invalid SOCKS5 domain destination")
		}
		out = append(out, 3, byte(len(destination.Fqdn)))
		out = append(out, destination.Fqdn...)
	default:
		return nil, errors.New("unsupported SOCKS5 destination address")
	}
	var port [2]byte
	binary.BigEndian.PutUint16(port[:], destination.Port)
	out = append(out, port[:]...)
	return out, nil
}

func writeSocks5UDPDatagram(conn *net.UDPConn, destination M.Socksaddr, payload []byte) error {
	address, err := socks5Address(destination)
	if err != nil {
		return err
	}
	frame := make([]byte, 3+len(address)+len(payload))
	copy(frame[3:], address)
	copy(frame[3+len(address):], payload)
	_, err = conn.Write(frame)
	return err
}

func parseSocks5UDPDatagram(packet []byte) ([]byte, error) {
	if len(packet) < 4 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
		return nil, errors.New("invalid SOCKS5 UDP datagram header")
	}
	idx := 3
	switch packet[idx] {
	case 1:
		idx += 1 + 4 + 2
	case 3:
		idx++
		if idx >= len(packet) {
			return nil, errors.New("truncated SOCKS5 UDP domain")
		}
		idx += 1 + int(packet[idx]) + 2
	case 4:
		idx += 1 + 16 + 2
	default:
		return nil, errors.New("invalid SOCKS5 UDP address type")
	}
	if idx > len(packet) {
		return nil, errors.New("truncated SOCKS5 UDP datagram")
	}
	return packet[idx:], nil
}

func readSocks5AddressAndPort(r io.Reader, atyp byte) (string, uint16, error) {
	var host string
	switch atyp {
	case 1:
		var b [4]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(b[:]).String()
	case 3:
		var n [1]byte
		if _, err := io.ReadFull(r, n[:]); err != nil || n[0] == 0 {
			return "", 0, errors.New("invalid SOCKS5 domain length")
		}
		b := make([]byte, n[0])
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	case 4:
		var b [16]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(b[:]).String()
	default:
		return "", 0, errors.New("invalid SOCKS5 address type")
	}
	var port [2]byte
	if _, err := io.ReadFull(r, port[:]); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(port[:]), nil
}

func writeAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}