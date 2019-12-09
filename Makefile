######################################################################
# USAGE:
#   1. run `make` to compile all files to bin/
#   2. run `make clean` to remove bin/
#   3. run `make run` to run the java program
######################################################################


##############
# variables  #
##############
# recursive find all java files, and remove beginning "./"
SRC := $(patsubst ./%, %, $(shell find . -name "*.java"))
OUT = bin
# change src/**/*.java to bin/**/*.class
LIST := $(SRC:src/%.java=$(OUT)/%.class)

#############
# commands  #
#############
all: $(LIST)

$(OUT)/%.class: src/%.java | $(OUT)
	javac -cp src -d $| $<

$(OUT):
	@mkdir $@

.PHONY: run
run: all
	@java -cp bin edu.wisc.cs.sdn.simpledns.SimpleDNS -r a.root-servers.net -e ec2.csv

.PHONY: clean
clean:
	@echo 'files removed:'
	@rm -rfv bin
