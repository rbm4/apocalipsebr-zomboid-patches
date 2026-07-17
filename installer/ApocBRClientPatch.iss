; ApocBR Project Zomboid Client Patch - Inno Setup Installer
; Builds a formal Windows installer that deploys the client patch.
;
; Build steps:
;   1. Install Inno Setup 6: https://jrsoftware.org/isdl.php
;   2. Open this .iss file in Inno Setup Compiler and click Build.
;   3. Distribute the resulting .exe from the dist\ folder.
;
; For signed distribution, purchase a code signing certificate and uncomment
; the SignTool line below (or configure it via Tools > Configure Sign Tools).

#define PatchVersion "42.19.0"
#define PatchName "ApocBR Client FPS Patch"
#define AppPublisher "Apocalipse [BR]"
#define AppURL "https://apocalipse.cloud"

[Setup]
AppName={#PatchName}
AppVersion={#PatchVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}
AppUpdatesURL={#AppURL}
DefaultDirName={autopf}\ApocBR\ClientPatch
DisableProgramGroupPage=yes
DisableReadyPage=no
;PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\dist
OutputBaseFilename=ApocBR_ClientPatch_{#PatchVersion}_Installer
SetupIconFile=..
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#PatchName}
UninstallDisplayIcon={app}\patch-client.bat
; Uncomment after configuring a SignTool in Inno Setup:
; SignTool=default

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; Patch launcher and auto-detection logic
Source: "..\patch-client.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\patch-client.bat"; DestDir: "{app}"; Flags: ignoreversion

; Patched Java sources and the compile/deploy script
Source: "..\42.19.0-client\*"; DestDir: "{app}\42.19.0-client"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{autoprograms}\ApocBR\{#PatchName}"; Filename: "{app}\patch-client.bat"
Name: "{autoprograms}\ApocBR\Revert {#PatchName}"; Filename: "{app}\patch-client.bat"; Parameters: "-Revert"

[Run]
; Apply the patch after files are installed. Runs as the current (admin) user.
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\patch-client.ps1"""; Description: "Apply the ApocBR client patch to Project Zomboid"; Flags: postinstall runascurrentuser

[UninstallRun]
; Revert the deployed .class overrides when the patch is uninstalled.
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\patch-client.ps1"" -Revert"; Flags: runascurrentuser

[Messages]
; Friendly confirmation text before the post-install patch step.
WelcomeLabel2=This will install the ApocBR client patch for Project Zomboid build {#PatchVersion}.%n%nThe installer will find your Steam copy of Project Zomboid and place the patched .class files next to the game JAR, leaving the original JAR untouched.%n%nIt is recommended to close Project Zomboid before continuing.

[CustomMessages]
; Optional: additional context shown in the finish page if the patch step is selected.
PatchNote=The patch will be applied to the Project Zomboid installation detected from Steam. If you uninstall this patch later, the original game files will be restored automatically.
