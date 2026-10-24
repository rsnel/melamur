# Release signing

Release APKs are signed with your own key. The keystore and its passwords never
go into the repository: Gradle reads them from `~/.gradle/gradle.properties`
(local builds) or from environment variables that the release workflow fills
from GitHub secrets. Without them, `assembleRelease` still works but produces an
unsigned APK.

## 1. Create the key (once, on your own machine)

```
keytool -genkeypair -v -keystore melamur-release.jks -alias melamur \
        -keyalg RSA -keysize 4096 -validity 10000
```

Modern keystores (PKCS12) use one password for both the store and the key, so
use the same password for both settings below.

**Back up `melamur-release.jks` and its password somewhere safe.** Android only
installs an update if it is signed with the same key; if the key is lost, every
user has to uninstall and reinstall the app.

## 2. Local signed builds (optional)

Add to `~/.gradle/gradle.properties` (not the project's file):

```
melamurKeystore=/home/you/keys/melamur-release.jks
melamurKeystorePassword=...
melamurKeyAlias=melamur
melamurKeyPassword=...
```

Then `gradle assembleRelease` writes
`app/build/outputs/apk/release/app-release.apk`.

## 3. GitHub secrets for the release workflow

```
base64 -w0 melamur-release.jks | gh secret set MELAMUR_KEYSTORE_BASE64 -R rsnel/melamur
gh secret set MELAMUR_KEYSTORE_PASSWORD -R rsnel/melamur
gh secret set MELAMUR_KEY_ALIAS -R rsnel/melamur --body melamur
gh secret set MELAMUR_KEY_PASSWORD -R rsnel/melamur
```

(`gh secret set` without `--body` prompts for the value, so the passwords do not
end up in your shell history. On macOS use `base64 -i melamur-release.jks`.)

## 4. Making a release

```
git tag v0.2.0
git push origin v0.2.0
```

The **Release** workflow builds the APK with version name `0.2.0`, checks the
signature with `apksigner`, and publishes a GitHub release with
`melamur.apk` attached. The newest release is always at
https://github.com/rsnel/melamur/releases/latest/download/melamur.apk. The version code is the workflow's run number, so
it always increases. Running the workflow by hand ("Run workflow") builds and
uploads a signed APK as an artifact without publishing a release.
