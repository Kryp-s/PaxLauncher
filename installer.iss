; PaxLauncher — інсталятор (Inno Setup 6). Збирається автоматично в GitHub Actions.
#define AppName "PaxLauncher"
#define AppVersion GetEnv("PAX_VERSION")
#if AppVersion == ""
  #define AppVersion "1.0.0"
#endif
#define AppExe "PaxLauncher.exe"
#define AppUrl "https://github.com/Kryp-s/PaxLauncher"

[Setup]
; AppId — унікальний ідентифікатор програми. НІКОЛИ не змінюйте: за ним нова версія замінює стару.
AppId={{5A8C984A-660E-4949-B499-4CB2E772AAAF}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Kryp-s
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}/issues
AppUpdatesURL={#AppUrl}/releases
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; встановлення лише для поточного користувача — без запиту прав адміністратора
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=dist
OutputBaseFilename=PaxLauncher-Setup-{#AppVersion}
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\{#AppExe}
LicenseFile=LICENSE
WizardStyle=modern
WizardImageFile=wizard.bmp
WizardSmallImageFile=small.bmp
Compression=lzma2/ultra
SolidCompression=yes
; мова майстра береться з мови Windows; якщо її немає серед нижчих — буде українська
ShowLanguageDialog=no
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "ukrainian"; MessagesFile: "compiler:Languages\Ukrainian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
; Свої тексти майстра. [name] — назва програми, [name/ver] — назва з версією, %n — новий рядок.
ukrainian.WelcomeLabel1=Ласкаво просимо до [name]!
ukrainian.WelcomeLabel2=Цей майстер встановить [name/ver] на ваш комп'ютер.%n%nPaxLauncher — український лаунчер Minecraft: усе для зручної гри та приємний дизайн.%n%nНатисніть «Далі», щоб продовжити.
ukrainian.FinishedHeadingLabel=Готово!
ukrainian.FinishedLabel=[name] встановлено. Гарної гри!
english.WelcomeLabel1=Welcome to [name]!
english.WelcomeLabel2=This will install [name/ver] on your computer.%n%nPaxLauncher is a Ukrainian Minecraft launcher.%n%nClick Next to continue.
english.FinishedHeadingLabel=All done!
english.FinishedLabel=[name] has been installed. Have fun!

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "zipdir\PaxLauncher\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
; Без skipifsilent: після тихого автооновлення лаунчер сам відкривається знову.
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall runasoriginaluser
