@echo off
set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set JAR=%PROJECT_ROOT%\\dist\\cocomut-cli.jar
set DEV_JAR=%PROJECT_ROOT%\\cocomut-cli\\target\\cocomut-cli-0.1.0-all.jar
if exist "%JAR%" (
  java -jar "%JAR%" %*
) else (
  if not exist "%DEV_JAR%" (
    mvn -q -f "%PROJECT_ROOT%\\pom.xml" -pl cocomut-cli -am -DskipTests package
  )
  java -jar "%DEV_JAR%" %*
)
