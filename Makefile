SHELL = bash
MVNOPTS = -Dmaven.javadoc.skip=true -Dmaven.test.skip=true -Dspotbugs.skip=true

default: tool/target/apdu4j.jar

tool/target/apdu4j.jar: $(shell find . -name '*.java')
	./mvnw verify

install: default
	./mvnw $(MVNOPTS) install

clean:
	./mvnw clean

fast: $(shell find . -name '*.java')
	./mvnw $(MVNOPTS) install
