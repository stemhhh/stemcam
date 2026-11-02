# NexoCam site (Cloudflare Pages)

Static landing + connect app.

## Deploy

1. Edit [`config.js`](config.js): `githubUrl`, `downloadUrl`, `wsUrl`, `siteUrl`.
2. Cloudflare Dashboard → Workers & Pages → Create → Pages → Upload / connect this folder.
3. Or:

```bash
npx wrangler pages deploy . --project-name=nexocam
```

Point **Descargar plugin** at your GitHub Releases URL after the first release.
