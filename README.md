docker compose up

docker compose run --rm neo4j neo4j-admin database import full neo4j --nodes=Node=/var/lib/neo4j/import/wanderwege_nodes.csv --relationships=/var/lib/neo4j/import/wanderwege_edges.csv --overwrite-destination

[Helm chart documentation](helm/trailr/README.md)

## Android APK releases

The `Release Android APK` GitHub Actions workflow builds a signed APK and
publishes it to a GitHub Release. It runs manually from **Actions** and asks
for a release tag such as `v1.0.0`.

Generate a signing keystore locally. No Google or Play Store account is
required:

```sh
keytool -genkeypair -v -keystore trailr-release.jks -alias trailr -keyalg RSA -keysize 2048 -validity 10000
```

Keep this file and its passwords backed up securely. Losing the keystore
prevents future APKs from being published as updates to the same app. Do not
commit it to the repository.

In the GitHub repository, open **Settings > Secrets and variables > Actions**
and add these repository secrets:

| Secret | Value |
| --- | --- |
| `API_URL` | Production API URL used by the Android app |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of `trailr-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password entered in `keytool` |
| `ANDROID_KEY_ALIAS` | `trailr` |
| `ANDROID_KEY_PASSWORD` | Key password entered in `keytool` |

On PowerShell, copy the base64-encoded keystore to the clipboard with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("trailr-release.jks")) | Set-Clipboard
```

Then open **Actions > Release Android APK > Run workflow**, enter a new tag,
and run it.
