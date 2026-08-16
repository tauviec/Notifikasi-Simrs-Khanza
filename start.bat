@echo off
cd /d "%~dp0"
start "" javaw --add-modules javafx.media -cp ".;NotifKhanzaClient.jar;mysql-connector-java-5.1.39-bin.jar" NotifKhanzaClient
