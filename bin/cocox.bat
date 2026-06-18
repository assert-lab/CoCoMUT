@echo off
set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set JAR=%PROJECT_ROOT%\\dist\\cocox-cli.jar
set DEV_JAR=%PROJECT_ROOT%\\cocox-cli\\target\\cocox-cli-1.0-SNAPSHOT-all.jar
if exist "%JAR%" (
  java -jar "%JAR%" %*
) else (
  if not exist "%DEV_JAR%" (
    mvn -q -f "%PROJECT_ROOT%\\pom.xml" -pl cocox-cli -am -DskipTests package
  )
  java -jar "%DEV_JAR%" %*
)
