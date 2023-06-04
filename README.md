# Wyr2

## To set up a new instance

```shell
# Run the following commands as root

# Create a private key
wg genkey > /etc/wireguard/privkey

# Change permissions on the file
chmod 600 /etc/wireguard/privkey

# Display the public key
wg pubkey < /etc/wireguard/privkey
```

Now add the public key to netmap.

## Logging

enable:
echo 'module wireguard +p' | sudo tee /sys/kernel/debug/dynamic_debug/control

view:
journalctl -xef

disable:
echo 'module wireguard -p' | sudo tee /sys/kernel/debug/dynamic_debug/control

source:
https://www.the-digital-life.com/wireguard-enable-logging/
