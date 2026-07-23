TZ = UTC # same as Github
export TZ
SHELL = bash
JDK := zulu
JAVA17 := /Library/Java/JavaVirtualMachines/$(JDK)-17.jdk/Contents/Home
JAVA21 := /Library/Java/JavaVirtualMachines/$(JDK)-21.jdk/Contents/Home
JAVA25 := /Library/Java/JavaVirtualMachines/$(JDK)-25.jdk/Contents/Home

# Default the SDK location (Android Studio convention per OS) so the -Pandroid profile
# self-activates when the jar is present. An explicit ANDROID_HOME always wins.
ANDROID_HOME ?= $(if $(filter Darwin,$(shell uname)),$(HOME)/Library/Android/sdk,$(HOME)/Android/Sdk)
export ANDROID_HOME

default: today reportjava
	./mvnw verify install

reportjava:
	@echo using java $(shell java -version 2>&1 | grep version) from \"$(JAVA_HOME)\"

17:
	JAVA_HOME=$(JAVA17) ./mvnw verify

21:
	JAVA_HOME=$(JAVA21) ./mvnw verify

25:
	JAVA_HOME=$(JAVA25) ./mvnw verify


all: 17 21 25

reuse:
	reuse --no-multiprocessing lint

today:
	# for a dirty tree, set the date to today
	test -z "$(shell git status --porcelain)" || ./mvnw versions:set -DnewVersion=$(shell date +%y.%m.%d)-SNAPSHOT -DgenerateBackupPoms=false
