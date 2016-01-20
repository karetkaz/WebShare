@echo off
:: usage java -jar server.jar [arguments] <shared_path>
:: -host <string>: override default: -host 'http://localhost'.
:: -port <number>: override default: -port '8090'.
:: -auth <string>: require username and password. ex: -auth 'User:secret1'.
:: -write: enable uploading, and deleting files from the shared directory.
:: -n <number>: override simultaneous requests: -n '256'.
:: -repo <url>: use as proxy, with write enabled caches the responses from server.

REM ~ change working directory to this files parent
cd %~dp0
set PATH="%JAVA_HOME%/bin";%PATH%

if exist "server.jar" goto runserver

:compile
mkdir "out"
echo compiling server ...
javac -cp "lib/sceye-fi.jar" -d out src/*.java
cd out
jar xf "../lib/sceye-fi.jar"
cd ..
jar cvfe "server.jar" WebShare -C out/ .

:runserver
REM ~ java -jar server.jar -write $*
java -jar server.jar $*

:: using as a file caching proxy: all visited pages will be saved locally
REM ~ SET REPO="https://qmlbook.github.io"
REM ~ java -jar server.jar -repo $REPO -write './qmlbook.github.io'
