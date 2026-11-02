# Install — Alpha (USB)

## Windows plugin (automatic — recommended)

1. Make sure `dist/obs-phone-cam-windows/` exists (already built in this repo).
2. Double-click [`installer/Install-OBSPhoneCam.bat`](../installer/Install-OBSPhoneCam.bat)
3. Accept the UAC / Administrator prompt.
4. The installer will:
   - close OBS if it is open
   - copy the plugin + FFmpeg DLLs into your OBS folder
   - copy locale/data files
   - try to register the WinUSB accessory driver
5. Open OBS → Add Source → **Phone Cam (USB)** → preset **Native Max**.

Manual copy is no longer required. Uninstall: run `installer/Uninstall-OBSPhoneCam.ps1` as admin.

### Optional: Inno Setup `.exe` installer

If you have [Inno Setup](https://jrsoftware.org/isinfo.php) installed:

```bat
iscc installer\OBSPhoneCam.iss
```

Output: `dist\OBSPhoneCam-Setup-1.0.0.exe`

## Android app

1. Enable “Install unknown apps” for your file manager / browser.
2. Install `OBSPhoneCam-android.apk`.
3. Grant **Camera** (and **Mic** if you want phone audio).
4. Open the app, then plug a **data** USB cable into the PC.
5. Tap **Start**, or enable auto-start in the OBS source.

## Quick verify

See [`qa-smoke.md`](qa-smoke.md).

## Common errors

| Message | Fix |
|---------|-----|
| Looking for Android USB… | Open the app, use a data cable, wait for accessory switch |
| Install WinUSB driver… | Zadig → select accessory → WinUSB |
| Decoder configure failed | Missing FFmpeg DLLs beside the plugin |
| Waiting for keyframe | Press **Request keyframe** in OBS source properties |
