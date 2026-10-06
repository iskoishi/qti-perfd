# PerfservicePrivilegePoC — Qualcomm perf HAL missing-authorization PoC
#
# A single Java file. Build against an Android SDK platform, then package and sign.
#
# Prerequisites:
#   ANDROID_HOME pointing at an Android SDK with a platform directory, e.g.:
#     ${ANDROID_HOME}/platforms/android-34/android.jar
#   a JDK with javac
#   android build-tools (aapt2, zipalign, apksigner) or jarsigner for signing
#
# The PoC is compile-verified against android-34 with javac. It has not been
# packaged or signed as part of this release; build and sign it yourself.

ANDROID_HOME ?= ${HOME}/Android/Sdk
PLATFORM     ?= android-34
BOOTCLASS    := ${ANDROID_HOME}/platforms/${PLATFORM}/android.jar

OUT := out
SRC := PerfservicePrivilegePoC.java

javac-check:
	@echo "== compile check against ${PLATFORM} =="
	mkdir -p ${OUT}/classes
	javac -source 11 -target 11 -classpath ${BOOTCLASS} -d ${OUT}/classes ${SRC}
	@echo "ok"

clean:
	rm -rf ${OUT}

.PHONY: javac-check clean
