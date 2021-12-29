# Wyr2

## To set up a new instance

```shell
# Run the following commands as root

# Create a private key
wg genkey > /etc/wireguard/privkey

# Change permissions on the file
chmod 066 /etc/wireguard/privkey

# Display the public key
wg pubkey < /etc/wireguard/privkey
```

Now add the public key to netmap.
