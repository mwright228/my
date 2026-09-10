def user_has_proto($p):
  if (.protocols == null or .protocols == [] or (.protocols | index("all") != null)) then true
  elif (.protocols | index($p) != null) then true
  elif ($p == "vless" and (.protocols | any(startswith("vless")))) then true
  elif ($p == "vmess" and (.protocols | any(startswith("vmess")))) then true
  elif ($p == "trojan" and (.protocols | any(startswith("trojan")))) then true
  elif ($p == "ss" and (.protocols | any(startswith("ss")))) then true
  elif (($p == "vless-ws" or $p == "vless_ws") and ((.protocols | index("vless") != null) or (.protocols | index("vless_ws") != null) or (.protocols | index("vless_ws_tls") != null) or (.protocols | index("vless_ws_ntls") != null))) then true
  elif (($p == "vless-httpupgrade" or $p == "vless_httpupgrade") and ((.protocols | index("vless") != null) or (.protocols | index("vless_httpupgrade") != null) or (.protocols | index("vless_httpupgrade_tls") != null) or (.protocols | index("vless_httpupgrade_ntls") != null))) then true
  elif (($p == "vless-xhttp" or $p == "vless_xhttp") and ((.protocols | index("vless") != null) or (.protocols | index("vless_xhttp") != null) or (.protocols | index("vless_xhttp_tls") != null))) then true
  elif (($p == "vless-grpc" or $p == "vless_grpc" or $p == "vless_grpc_tls") and ((.protocols | index("vless") != null) or (.protocols | index("vless_grpc") != null) or (.protocols | index("vless_grpc_tls") != null))) then true
  elif (($p == "vless-tcp" or $p == "vless_tcp" or $p == "vless_tcp_tls") and ((.protocols | index("vless") != null) or (.protocols | index("vless_tcp") != null) or (.protocols | index("vless_tcp_tls") != null))) then true
  elif (($p == "vmess-ws" or $p == "vmess_ws") and ((.protocols | index("vmess") != null) or (.protocols | index("vmess_ws") != null) or (.protocols | index("vmess_ws_tls") != null) or (.protocols | index("vmess_ws_ntls") != null))) then true
  elif (($p == "trojan-ws" or $p == "trojan_ws") and ((.protocols | index("trojan") != null) or (.protocols | index("trojan_ws") != null) or (.protocols | index("trojan_ws_tls") != null) or (.protocols | index("trojan_ws_ntls") != null))) then true
  elif (($p == "ss-ws" or $p == "ss_ws") and ((.protocols | index("ss") != null) or (.protocols | index("shadowsocks") != null) or (.protocols | index("ss_ws") != null) or (.protocols | index("ss_ws_tls") != null) or (.protocols | index("ss_ws_ntls") != null))) then true
  elif (($p == "ss-tcp" or $p == "ss_tcp") and ((.protocols | index("ss") != null) or (.protocols | index("shadowsocks") != null) or (.protocols | index("ss_tcp") != null))) then true
  elif (($p == "ss-2022" or $p == "ss_2022") and ((.protocols | index("ss") != null) or (.protocols | index("shadowsocks") != null) or (.protocols | index("ss_2022") != null) or (.protocols | index("shadowtls") != null))) then true
  elif ($p == "shadowtls" and ((.protocols | index("shadowtls") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "hysteria2" and ((.protocols | index("hysteria2") != null) or (.protocols | index("hy2") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "ssh" and ((.protocols | index("ssh") != null))) then true
  elif ($p == "openvpn" and ((.protocols | index("openvpn") != null) or (.protocols | index("vpn") != null))) then true
  elif (($p == "wireguard" or $p == "amneziawg" or $p == "awg") and ((.protocols | index("wireguard") != null) or (.protocols | index("wg") != null) or (.protocols | index("amneziawg") != null) or (.protocols | index("awg") != null) or (.protocols | index("vpn") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "amneziawg" and ((.protocols | index("amneziawg") != null) or (.protocols | index("awg") != null) or (.protocols | index("wireguard") != null) or (.protocols | index("wg") != null) or (.protocols | index("vpn") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "awg" and ((.protocols | index("amneziawg") != null) or (.protocols | index("awg") != null) or (.protocols | index("wireguard") != null) or (.protocols | index("wg") != null) or (.protocols | index("vpn") != null))) then true
  elif (($p == "squid" or $p == "chameleon") and ((.protocols | index("squid") != null) or (.protocols | index("chameleon") != null))) then true
  elif ($p == "zivpn" and ((.protocols | index("zivpn") != null) or (.protocols | index("antidpi") != null))) then true
  elif ($p == "tuic" and ((.protocols | index("tuic") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  elif ($p == "tbrutal" and ((.protocols | index("tbrutal") != null) or (.protocols | index("antidpi") != null) or (.protocols | index("stealth") != null))) then true
  else false
  end;
