mv /etc/wireguard/{{ifname}}.conf /etc/wireguard/{{ifname}}-bu.conf
touch /etc/wireguard/{{ifname}}.conf
chmod 066 /etc/wireguard/{{ifname}}.conf
echo "{{config-str}}" | sed "s/__PRIVKEY__/$(sed -e 's/[^a-zA-Z0-9,._+@%-]/\\&/g; 1{$s/^$/""/}; 1!s/^/"/; $!s/$/"/' /etc/wireguard/privkey)/" > /etc/wireguard/{{ifname}}.conf
systemctl restart wg-quick@wg0
