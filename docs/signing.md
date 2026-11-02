# Signing

## Android release keystore

Do **not** commit the keystore or passwords.

1. Create once:

```bat
keytool -genkeypair -v -keystore opcm-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias opcm
```

2. Create `android-app/keystore.properties` (gitignored):

```
storeFile=../opcm-release.jks
storePassword=***
keyAlias=opcm
keyPassword=***
```

3. `gradlew.bat assembleRelease`

## Windows installer / DLL

With a code-signing certificate:

```bat
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a dist\OBSPhoneCam-Setup-1.0.0.exe
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a dist\obs-phone-cam-windows\obs-plugins\64bit\obs-phone-cam.dll
```

Until a cert is available, ship Beta as sideload with SmartScreen warning noted in the release notes.
