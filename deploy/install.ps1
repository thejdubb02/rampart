# Rampart installer.
#
# Started life as the one Conveyor generates, and is published over it after every build
# because the generated one cannot install while Rampart is running: Windows answers
# 0x80073D02, "the package could not be installed because resources it modifies are
# currently in use", and names the copy that is open. That is exactly what happens when
# somebody re-runs this line to update, which is the only reason most people run it twice.
#
# The fix is the same flag the in-app restart button uses.

$CertName = "CN=Rampart"
$FSName = "rampart"
$PackageName = "Rampart"
$Site = "https://github.com/thejdubb02/rampart/releases/latest/download"

$CertURL = "${Site}/${FSName}.crt"

# The signing certificate has to be trusted before Windows will install a package signed
# with it, and it cannot go in the per-user store, so this is the one admin prompt. Only
# on a machine that has not seen it before.
if (!((dir cert:\LocalMachine\TrustedPeople).Subject -contains $CertName))
{
    if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator"))
    {
        Write-Output "Attempting to add root certificate ..."
        $cert = $Env:Temp + "\${FSName}.crt"
        Invoke-WebRequest -Uri "$CertURL" -OutFile $cert
        $cmd = "Import-Certificate -FilePath '$cert' -CertStoreLocation `"Cert:\LocalMachine\TrustedPeople`";Start-Sleep -s 2"
        Start-Process powershell -ArgumentList "$cmd" -Wait -Verb runAs
    }
}

Write-Output "Installing Rampart. A running copy will be closed and reopened."

# ForceTargetApplicationShutdown is the whole difference from the generated script. Without
# it this fails outright rather than replacing what is running.
Add-AppxPackage -AppInstallerFile "${Site}/${FSName}.appinstaller" -ForceTargetApplicationShutdown

start "shell:appsFolder\Rampart_sba2tsng0ttce!Rampart"
