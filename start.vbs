Set objShell = CreateObject("WScript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")
strFolder = objFSO.GetParentFolderName(WScript.ScriptFullName)
objShell.CurrentDirectory = strFolder
objShell.Run "javaw --add-modules javafx.media -cp "".;NotifKhanzaClient.jar;mysql-connector-java-5.1.39-bin.jar"" NotifKhanzaClient", 0, False
