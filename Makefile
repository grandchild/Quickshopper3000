.PHONY: all app api cli debug
all: app api cli

app:
	./gradlew assembleRelease

api cli:
	cargo build --release

debug:
	./gradlew installDebug && adb shell am force-stop org.effekthasch.quickshopper3k && adb shell am start -n org.effekthasch.quickshopper3k/.MainActivity