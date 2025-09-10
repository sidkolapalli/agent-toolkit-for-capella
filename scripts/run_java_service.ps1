Param(
    [string]$MavenPath = "tools/apache-maven-3.9.4/bin/mvn.cmd",
    [string]$JavaHome = $env:JAVA_HOME
)

if (-not (Test-Path $MavenPath)) { Write-Error "Maven not found at $MavenPath"; exit 1 }

if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
    Write-Warning "JAVA_HOME is not set. Set it to your JDK 17 installation, e.g.:"
    Write-Host "`$env:JAVA_HOME='C:\\Program Files\\Java\\jdk-17'"
}

Write-Host "Using JAVA_HOME=$JavaHome"
& $MavenPath -f src/java spring-boot:run

