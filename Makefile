.PHONY: all app api cli
all: app api cli

app:
	./gradlew assembleRelease

api cli:
	cargo build --release