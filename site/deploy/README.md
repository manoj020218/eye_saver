# Deploying eyesaver.iotsoft.in

Static site (no build step, no JavaScript). Pages:

| URL | File | Used in Play Console for |
| --- | --- | --- |
| `https://eyesaver.iotsoft.in/` | `site/index.html` | Website |
| `https://eyesaver.iotsoft.in/privacy/` | `site/privacy/index.html` | Privacy policy URL |
| `https://eyesaver.iotsoft.in/support/` | `site/support/index.html` | Support / contact |
| `https://eyesaver.iotsoft.in/delete-data/` | `site/delete-data/index.html` | Data safety → data deletion |

## 1. DNS (GoDaddy)

GoDaddy → My Products → `iotsoft.in` → DNS → Add record:

- Type: `A`
- Name: `eyesaver`
- Value: `<VPS public IPv4>`
- TTL: 1 hour (default)

Add an `AAAA` record too only if the VPS has IPv6 and nginx listens on it. Check propagation:

```bash
nslookup eyesaver.iotsoft.in
```

## 2. Upload the files (from the repository root, on Windows)

```powershell
ssh user@VPS "sudo mkdir -p /var/www/eyesaver.iotsoft.in && sudo chown $USER /var/www/eyesaver.iotsoft.in"
scp -r site/index.html site/404.html site/robots.txt site/sitemap.xml site/assets site/privacy site/support site/delete-data user@VPS:/var/www/eyesaver.iotsoft.in/
scp site/deploy/eyesaver.iotsoft.in.conf user@VPS:/tmp/
```

Do not upload the `deploy/` folder to the web root.

## 3. Enable the nginx site (on the VPS)

```bash
sudo mv /tmp/eyesaver.iotsoft.in.conf /etc/nginx/sites-available/eyesaver.iotsoft.in
sudo ln -s /etc/nginx/sites-available/eyesaver.iotsoft.in /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

(On CentOS/Rocky-style layouts, put the file in `/etc/nginx/conf.d/eyesaver.iotsoft.in.conf` instead.)

## 4. HTTPS certificate (after DNS resolves to the VPS)

```bash
sudo certbot --nginx -d eyesaver.iotsoft.in --redirect
sudo certbot renew --dry-run
```

## 5. Verify

```bash
curl -I https://eyesaver.iotsoft.in/privacy/      # expect HTTP/2 200
curl -I http://eyesaver.iotsoft.in/privacy/       # expect 301 to https
```

Open the privacy URL in a phone browser too, then paste it into Play Console → App content → Privacy policy.

## Updating later

Edit the HTML in `site/`, bump the "Effective date" in `privacy/index.html` when the policy meaning changes, and re-run the `scp` command from step 2.
