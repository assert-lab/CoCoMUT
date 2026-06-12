@echo off
set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set JAR=%PROJECT_ROOT%\\dist\\context4docugen-cli.jar
set DEV_JAR=%PROJECT_ROOT%\\context4docugen-cli\\target\\context4docugen-cli-1.0-SNAPSHOT-all.jar
if exist "%JAR%" (
  java -jar "%JAR%" %*
) else (
  if not exist "%DEV_JAR%" (
    mvn -q -f "%PROJECT_ROOT%\\pom.xml" -pl context4docugen-cli -am -DskipTests package
  )
  java -jar "%DEV_JAR%" %*
)
