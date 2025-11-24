TZ = UTC # same as Github
export TZ
SHELL = bash
JDK := zulu
JAVA17 := /Library/Java/JavaVirtualMachines/$(JDK)-17.jdk/Contents/Home
JAVA21 := /Library/Java/JavaVirtualMachines/$(JDK)-21.jdk/Contents/Home
JAVA25 := /Library/Java/JavaVirtualMachines/$(JDK)-25.jdk/Contents/Home

default: today reportjava
	./mvnw verify

reportjava:
	@echo using java $(shell java -version 2>&1 | grep version) from \"$(JAVA_HOME)\"

17:
	JAVA_HOME=$(JAVA17) ./mvnw verify

21:
	JAVA_HOME=$(JAVA21) ./mvnw verify

25:
	JAVA_HOME=$(JAVA25) ./mvnw verify


all: 17 21 25

today:
	# for a dirty tree, set the date to today
	test -z "$(shell git status --porcelain)" || ./mvnw versions:set -DnewVersion=$(shell date +%y.%m.%d)-SNAPSHOT -DgenerateBackupPoms=false
