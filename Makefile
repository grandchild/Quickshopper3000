all: app api cli

VERSION=$(shell sed -n 's/^version=//p' version.properties)
BUILD=$(shell sed -n 's/^build=//p' version.properties)
TAG=v${VERSION}-${BUILD}
APK_OUTFILE=app/build/outputs/apk/debug/qs3k-${TAG}-debug-unsigned.apk

app: ${APK_OUTFILE}

${APK_OUTFILE}:
	./gradlew assembleDebug

api cli:
	cargo build --release

debug:
	./gradlew installDebug && adb shell am force-stop org.effekthasch.quickshopper3k && adb shell am start -n org.effekthasch.quickshopper3k/.MainActivity

release: all
	jj split  # bumb build number
	sleep 1  # wait for .git/index.lock to be released
	jj bookmark set -r @- main
	git tag -f ${TAG}
	git push --tags
	gh release create ${TAG} --verify-tag --draft --generate-notes \
		$(APK_OUTFILE) \
		target/release/qs3k-api# \
		target/release/qs3k

clean:
	./gradlew clean
	cargo clean

.PHONY: all app api cli debug release clean
