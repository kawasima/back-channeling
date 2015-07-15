@echo off

pushd %0\..\..

set /p VERSION=<VERSION

java -cp dist\back-channeling-%VERSION%.jar;"lib\*" clojure.main -m back-channeling.core

pause

