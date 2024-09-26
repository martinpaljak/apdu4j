TZ = UTC # same as Github
export TZ
SHELL = bash
MVNOPTS = -Dmaven.javadoc.skip=true -Dmaven.test.skip=true -Dspotbugs.skip=true

SOURCES = $(shell find . -name '*.java' -o -name 'pom.xml')


default: tool/target/apdu4j.jar

tool/target/apdu4j.jar: $(SOURCES)
	./mvnw $(MVNOPTS) package

dep: $(SOURCES)
	./mvnw $(MVNOPTS) install

test: $(SOURCES)
	./mvnw verify

clean:
	./mvnw clean
