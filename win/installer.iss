#define AppVersion "0.1.1"

[Setup]
AppId={{63D81292-B029-4187-B608-099D5E187703}
AppName=网页电视
AppVersion={#AppVersion}
AppPublisher=NativeWasmTv
AppPublisherURL=https://github.com/buhanzhe/NativeWasmTv
DefaultDirName={localappdata}\Programs\NativeWasmTv
DefaultGroupName=网页电视
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=out\windows
OutputBaseFilename=NativeWasmTv-win-x64-v{#AppVersion}-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\NativeWasmTv.exe
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Files]
Source: "out\windows\NativeWasmTv-win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\网页电视"; Filename: "{app}\NativeWasmTv.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\网页电视"; Filename: "{app}\NativeWasmTv.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\NativeWasmTv.exe"; Description: "Launch NativeWasmTv"; Flags: nowait postinstall skipifsilent
