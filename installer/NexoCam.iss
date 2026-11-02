; Windows installer for NexoCam
; Builds: dist\NexoCam-Windows-Setup-1.0.0.exe

#define MyAppName "NexoCam"
#define MyAppVersion "1.0.1"
#define MyAppPublisher "NexoCam"
#define MyAppURL "https://github.com/stemhhh/nexocam"

[Setup]
AppId={{B7E4D91A-3C2F-4A6E-9B18-5D0F7A2C8E11}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\NexoCam
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
OutputDir=..\dist
OutputBaseFilename=NexoCam-Windows-Setup-{#MyAppVersion}
Compression=lzma
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayName={#MyAppName} for OBS Studio
InfoBeforeFile=..\docs\nexocam-installer-info.txt
CloseApplications=force
CloseApplicationsFilter=obs64.exe,obs32.exe,obs.exe

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Files]
Source: "..\dist\nexocam-windows\obs-plugins\64bit\*"; DestDir: "{code:GetObsPluginsDir}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\dist\nexocam-windows\data\obs-plugins\nexocam\*"; DestDir: "{code:GetObsDataPluginDir}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\docs\nexocam-installer-info.txt"; DestDir: "{app}"; DestName: "README.txt"; Flags: ignoreversion
Source: "..\docs\privacy-nexocam.md"; DestDir: "{app}"; DestName: "PRIVACY.txt"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName} notes"; Filename: "{app}\README.txt"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "{commonpf64}\obs-studio\bin\64bit\obs64.exe"; \
  Description: "Open OBS Studio"; \
  Flags: nowait postinstall skipifsilent unchecked; \
  Check: ObsExists

[Code]
function GetObsRoot(Param: string): string;
var
  P: string;
begin
  P := ExpandConstant('{commonpf64}\obs-studio');
  if DirExists(P + '\obs-plugins\64bit') then begin
    Result := P;
    exit;
  end;
  P := ExpandConstant('{commonpf}\obs-studio');
  if DirExists(P + '\obs-plugins\64bit') then begin
    Result := P;
    exit;
  end;
  Result := ExpandConstant('{commonpf64}\obs-studio');
end;

function GetObsPluginsDir(Param: string): string;
begin
  Result := GetObsRoot('') + '\obs-plugins\64bit';
end;

function GetObsDataPluginDir(Param: string): string;
begin
  Result := GetObsRoot('') + '\data\obs-plugins\nexocam';
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
  Exec('taskkill.exe', '/IM obs64.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/IM obs32.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/IM obs.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(1000);
  Result := '';
end;
