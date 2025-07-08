TZ = UTC # same as Github
export TZ
SHELL = bash
MVNOPTS = -Dmaven.javadoc.skip=true -Dmaven.test.skip=true -Dspotbugs.skip=true

SOURCES = $(shell find . -name '*.java' -o -name 'pom.xml')


default: today tool/target/apdu4j.jar

tool/target/apdu4j.jar: $(SOURCES)
	./mvnw $(MVNOPTS) package

dep: $(SOURCES)
	./mvnw $(MVNOPTS) install

test: $(SOURCES)
	./mvnw verify

clean:
	./mvnw clean

today:
	# for a dirty tree, set the date to today
	test -z "$(shell git status --porcelain)" || ./mvnw versions:set -DnewVersion=$(shell date +%y.%m.%d)-SNAPSHOT -DgenerateBackupPoms=false
