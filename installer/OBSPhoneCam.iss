; Single Windows installer for OBS Phone Cam
; Builds: dist\OBSPhoneCam-Windows-Setup-1.0.0.exe
; Double-click → UAC → installs plugin + FFmpeg + locale + WinUSB helper

#define MyAppName "OBS Phone Cam"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "OBSPhoneCam"
#define MyAppURL "https://github.com/obsphonecam/obs-phone-cam"

[Setup]
AppId={{8F3C2A11-0B0C-4E5A-9D7E-1A2B3C4D5E6F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\OBSPhoneCam
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
OutputDir=..\dist
OutputBaseFilename=OBSPhoneCam-Windows-Setup-{#MyAppVersion}
Compression=lzma
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
SetupIconFile=
UninstallDisplayName={#MyAppName} for OBS Studio
InfoBeforeFile=..\docs\installer-windows-info.txt
CloseApplications=force
CloseApplicationsFilter=obs64.exe,obs32.exe,obs.exe

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Files]
; Plugin + FFmpeg → OBS plugins folder
Source: "..\dist\obs-phone-cam-windows\obs-plugins\64bit\*"; DestDir: "{code:GetObsPluginsDir}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Locale/data → OBS data folder
Source: "..\dist\obs-phone-cam-windows\data\obs-plugins\obs-phone-cam\*"; DestDir: "{code:GetObsDataPluginDir}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Keep helpers + docs under Program Files\OBSPhoneCam
Source: "winusb\*"; DestDir: "{app}\winusb"; Flags: ignoreversion
Source: "..\docs\install-alpha.md"; DestDir: "{app}"; DestName: "INSTALL.txt"; Flags: ignoreversion
Source: "..\docs\THIRD_PARTY_FFMPEG.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\docs\installer-windows-info.txt"; DestDir: "{app}"; DestName: "README.txt"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName} notes"; Filename: "{app}\README.txt"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "powershell.exe"; \
  Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\winusb\install-winusb.ps1"""; \
  StatusMsg: "Registering USB driver for the phone…"; \
  Flags: runhidden waituntilterminated; \
  Description: "Register WinUSB driver (recommended)"
Filename: "{pf64}\obs-studio\bin\64bit\obs64.exe"; \
  Description: "Open OBS Studio"; \
  Flags: nowait postinstall skipifsilent unchecked; \
  Check: ObsExists

[Code]
function GetObsRoot(Param: string): string;
var
  P: string;
begin
  P := ExpandConstant('{pf64}\obs-studio');
  if DirExists(P + '\obs-plugins\64bit') then begin
    Result := P;
    exit;
  end;
  P := ExpandConstant('{pf}\obs-studio');
  if DirExists(P + '\obs-plugins\64bit') then begin
    Result := P;
    exit;
  end;
  Result := ExpandConstant('{pf64}\obs-studio');
end;

function GetObsPluginsDir(Param: string): string;
begin
  Result := GetObsRoot('') + '\obs-plugins\64bit';
end;

function GetObsDataPluginDir(Param: string): string;
begin
  Result := GetObsRoot('') + '\data\obs-plugins\obs-phone-cam';
end;

function ObsExists(): Boolean;
begin
  Result := FileExists(GetObsRoot('') + '\bin\64bit\obs64.exe');
end;

function InitializeSetup(): Boolean;
begin
  if not DirExists(GetObsRoot('') + '\obs-plugins\64bit') then begin
    MsgBox('OBS Studio was not found in Program Files.'#13#10 +
           'Install OBS Studio first, then run this installer again.',
           mbError, MB_OK);
    Result := False;
  end else
    Result := True;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  // Force-close OBS so DLL copy cannot fail as "in use"
  Exec('taskkill.exe', '/IM obs64.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/IM obs32.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/IM obs.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(1000);
  Result := '';
end;
