#!/bin/bash
set -x

function fatal {
  if [[ -z $PROFILE ]]; then
      comment_on_pull "Tests failed ($BUILD_URL): $1"
  else
      comment_on_pull "$PROFILE profile tests failed ($BUILD_URL): $1"
  fi

  echo "$1"
  exit 1
}

function which_java {
  type -p java 2>&1 > /dev/null
  if [ $? = 0 ]; then
    _java=java
  elif [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
    _java="$JAVA_HOME/bin/java"
  else
    unset _java
  fi

  if [[ "$_java" ]]; then
    version=$("$_java" -version 2>&1 | grep -oP 'version "?(1\.)?\K\d+' || true)
    echo $version
  fi
}

# return 0 if using the IBM java compiler
function is_ibm {
  jvendor=$(java -XshowSettings:properties -version 2>&1 | awk -F '"' '/java.vendor = / {print $1}')
  [[ $jvendor == *"IBM Corporation"* ]]
}

# check the JDK version and disable the WildFly clone, build and test when its minimum JDK version is higher 
# see https://issues.redhat.com/browse/WFLY-18967
function wildfly_minimum_jdk {
  if [ "$_jdk" -lt 17 ]; then
    echo "WildFly must be built with JDK 17 or greater" 
    export AS_BUILD=0 AS_CLONE=0 AS_TESTS=0 LRA_AS_TESTS=0 ARQ_PROF=no_arq 
    # without AS test results the code coverage does not work, so not running it
    export CODE_COVERAGE=0
  fi
}
function get_pull_xargs {
  rval=0
  res=$(echo $1 | sed 's/\\r\\n/ /g')
  res=$(echo $res | sed 's/"/ /g')
  OLDIFS=$IFS
  IFS=', ' read -r -a array <<< "$res"
  echo "get_pull_xargs: parsing $1"

  for element in "${array[@]}"
  do
    if [[ $element == *"="* ]]; then
      if [[ $element == "PROFILE="* ]]; then
        echo "comparing PROFILE=$2 with $element"
        if [[ ! "PROFILE=$2" == $element ]]; then
          echo "SKIPING PROFILE $2"
          rval=1
        fi
      else
        echo "exporting $element"
        export $element
      fi
    fi
  done

  IFS=$OLDIFS

  return $rval
}

function init_test_options {
    is_ibm
    ISIBM=$?

    _jdk=`which_java`
    if [ "$_jdk" -lt 17 ]; then
      fatal "Narayana does not support JDKs less than 17"
    fi

    [ $LRA_CURRENT_VERSION ] || LRA_CURRENT_VERSION=`awk '/lra-parent/ { while(!/<version>/) {getline;} print; }' pom.xml | cut -d \< -f 2|cut -d \> -f 2`
    [ $CODE_COVERAGE ] || CODE_COVERAGE=0
    [ x"$CODE_COVERAGE_ARGS" != "x" ] || CODE_COVERAGE_ARGS=""
    [ $ARQ_PROF ] || ARQ_PROF=arq	# IPv4 arquillian profile
    [ $ENABLE_LRA_TRACE_LOGS ] || ENABLE_LRA_TRACE_LOGS=" -Dtest.logs.to.file=true -Dtrace.lra.coordinator"

    if ! get_pull_xargs "$PULL_DESCRIPTION_BODY" $PROFILE; then # see if the PR description overrides the profile
        echo "SKIPPING PROFILE=$PROFILE"
        export COMMENT_ON_PULL=""
        export AS_BUILD=0 AS_CLONE=0 AS_TESTS=0 NARAYANA_BUILD=0 NARAYANA_TESTS=0 
        export LRA_TESTS=0 LRA_AS_TESTS=0
    elif [[ $PROFILE == "CORE" ]]; then
        if [[ ! $PULL_DESCRIPTION_BODY == *!MAIN* ]] && [[ ! $PULL_DESCRIPTION_BODY == *!CORE* ]]; then
          comment_on_pull "Started testing this pull request with $PROFILE profile: $BUILD_URL"
          export AS_BUILD=1 AS_CLONE=1 AS_TESTS=0 NARAYANA_BUILD=1 NARAYANA_TESTS=1 
          export LRA_TESTS=0 LRA_AS_TESTS=0
        else
          export COMMENT_ON_PULL=""
        fi
    elif [[ $PROFILE == "AS_TESTS" ]]; then
        if [[ ! $PULL_DESCRIPTION_BODY == *!AS_TESTS* ]]; then
          if [[ "$_jdk" -lt 17 ]]; then
            fatal "Requested JDK version $_jdk cannot run with axis $PROFILE: please use jdk 17 instead"
          fi
          comment_on_pull "Started testing this pull request with $PROFILE profile: $BUILD_URL"
          export AS_BUILD=0 AS_CLONE=1 AS_TESTS=1 NARAYANA_BUILD=1 NARAYANA_TESTS=0 
          export LRA_TESTS=0 LRA_AS_TESTS=0
        else
          export COMMENT_ON_PULL=""
        fi

    elif [[ $PROFILE == "JACOCO" ]]; then
        if [[ ! $PULL_DESCRIPTION_BODY == *!JACOCO* ]]; then
          if [[ "$_jdk" -lt 17 ]]; then
            fatal "Requested JDK version $_jdk cannot run with axis $PROFILE: please use jdk 17 instead"
          fi
          comment_on_pull "Started testing this pull request with JACOCO profile: $BUILD_URL"
          export AS_BUILD=1 AS_CLONE=1 AS_TESTS=0 NARAYANA_BUILD=1 NARAYANA_TESTS=1 
          export LRA_TESTS=1 LRA_AS_TESTS=0 CODE_COVERAGE=1 CODE_COVERAGE_ARGS="-PcodeCoverage -Pfindbugs"
          [ -z ${MAVEN_OPTS+x} ] && export MAVEN_OPTS="-Xms2048m -Xmx2048m"
        else
          export COMMENT_ON_PULL=""
        fi

    elif [[ $PROFILE == "LRA" ]]; then
        if [[ ! $PULL_DESCRIPTION_BODY == *!LRA* ]]; then
          comment_on_pull "Started testing this pull request with LRA profile: $BUILD_URL"
          export AS_BUILD=1 AS_CLONE=1 AS_TESTS=0 NARAYANA_BUILD=1 NARAYANA_TESTS=0
          export LRA_TESTS=1 LRA_AS_TESTS=1
        else
          export COMMENT_ON_PULL=""
        fi
    else
        export COMMENT_ON_PULL=""
        comment_on_pull "Started testing this pull request with $PROFILE profile: $BUILD_URL"
    fi
    wildfly_minimum_jdk
    [ $NARAYANA_TESTS ] || NARAYANA_TESTS=0	# run the narayana surefire tests
    [ $NARAYANA_BUILD ] || NARAYANA_BUILD=0 # build narayana
    [ $AS_CLONE ] && [ -z "$WILDFLY_CLONED_REPO" ] || AS_CLONE=0 # git clone the AS repo when WILDFLY_CLONED_REPO is not provided
    [ $AS_BUILD ] || AS_BUILD=0 # build the AS
    [ $AS_TESTS ] || AS_TESTS=0 # Run WildFly/JBoss EAP testsuite
    [ $LRA_AS_TESTS ] || LRA_AS_TESTS=0 #LRA tests
    [ $LRA_TESTS ] || LRA_TESTS=1 # LRA Test

    [ $REDUCE_SPACE ] || REDUCE_SPACE=0 # Whether to reduce the space used

    get_pull_xargs "$PULL_DESCRIPTION_BODY" $PROFILE # see if the PR description overrides any of the defaults

    JAVA_VERSION=$(java -version 2>&1 | grep "\(java\|openjdk\) version" | cut -d\  -f3 | tr -d '"' | tr -d '[:space:]' | awk -F . '{if ($1==1) print $2; else print $1}')
}

function initGithubVariables
{
     [ "$PULL_NUMBER" = "" ] &&\
         PULL_NUMBER=$(echo $GIT_BRANCH | awk -F 'pull' '{ print $2 }' | awk -F '/' '{ print $2 }')

     if [ "$PULL_NUMBER" != "" ]
     then
         [ "x${PULL_DESCRIPTION}" = "x" ] &&\
             PULL_DESCRIPTION=$(curl -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/repos/$GIT_ACCOUNT/$GIT_REPO/pulls/$PULL_NUMBER)
         [ "x${PULL_DESCRIPTION_BODY}" = "x" ] &&\
             PULL_DESCRIPTION_BODY=$(printf '%s' "$PULL_DESCRIPTION" | grep \"body\":)
     else
             PULL_DESCRIPTION=""
             PULL_DESCRIPTION_BODY=""
     fi
}

function comment_on_pull
{
    if [ "$COMMENT_ON_PULL" = "" ]; then echo $1; return; fi

    if [ "$PULL_NUMBER" != "" ]
    then
        JSON="{ \"body\": \"$1\" }"
        curl -d "$JSON" -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/repos/$GIT_ACCOUNT/$GIT_REPO/issues/$PULL_NUMBER/comments
    else
        echo "Not a pull request, so not commenting"
    fi
}

function check_if_pull_closed
{
    if [ "$PULL_NUMBER" != "" ]
    then
      if [[ $PULL_DESCRIPTION =~ "\"state\": \"closed\"" ]]
      then
          echo "pull closed"
          exit 0
      else
          echo "pull open"
      fi
    fi
}

function check_if_pull_noci_label
{
    if [ "$PULL_NUMBER" != "" ]
    then
        if [[ $PULL_DESCRIPTION =~ "\"name\": \"NoCI\"" ]]
        then
            echo "pull request $PULL_NUMBER is defined with NoCI label, exiting this CI execution"
            exit 0
        else
            echo "NoCI label is not present at the pull request $PULL_NUMBER"
        fi
    fi
}

function build_narayana {
  echo "Checking if need SPI PR"
  if [ -n "$SPI_BRANCH" ]; then
    echo "Building SPI PR"
    if [ -d jboss-transaction-spi ]; then
      rm -rf jboss-transaction-spi
    fi
    git clone https://github.com/jbosstm/jboss-transaction-spi.git -o jbosstm
    [ $? -eq 0 ] || fatal "git clone https://github.com/jbosstm/jboss-transaction-spi.git failed"
    cd jboss-transaction-spi
    git fetch jbosstm +refs/pull/*/head:refs/remotes/jbosstm/pull/*/head
    [ $? -eq 0 ] || fatal "git fetch of pulls failed"
    git checkout $SPI_BRANCH
    [ $? -eq 0 ] || fatal "git fetch of pull branch failed"
    cd ../
    ./build.sh -f jboss-transaction-spi/pom.xml -B clean install
    [ $? -eq 0 ] || fatal "Build of SPI failed"
  fi

  echo "Building Narayana"
  cd $WORKSPACE

  [ $NARAYANA_TESTS = 1 ] && NARAYANA_ARGS= || NARAYANA_ARGS="-DskipTests"

  echo "Using MAVEN_OPTS: $MAVEN_OPTS"

  ./build.sh -B -Prelease$OBJECT_STORE_PROFILE $ORBARG "$@" $NARAYANA_ARGS $IPV6_OPTS $CODE_COVERAGE_ARGS clean install

  [ $? -eq 0 ] || fatal "narayana build failed"

  return 0
}

function clone_as {
  echo "Cloning AS sources from https://github.com/jbosstm/jboss-as.git"

  cd ${WORKSPACE}
  if [ -d jboss-as ]; then
    echo "Using existing checkout of WildFly. If a fresh build should be used, delete the folder ${WORKSPACE}/jboss-as"
    cd jboss-as
    git fetch jbosstm
    [ $? -eq 0 ] || fatal "Fetching from jbosstm remote did not work"
    echo "Rebasing the local branch on top of jbosstm/main"
    git pull --rebase jbosstm main
    [ $? -eq 0 ] || fatal "git rebase jbosstm failed"
  else
    echo "First time checkout of WildFly"
    git clone https://github.com/jbosstm/jboss-as.git -o jbosstm
    [ $? -eq 0 ] || fatal "git clone https://github.com/jbosstm/jboss-as.git failed"

    cd jboss-as

    git remote add upstream https://github.com/wildfly/wildfly.git

    [ -z "$AS_BRANCH" ] || git fetch jbosstm +refs/pull/*/head:refs/remotes/jbosstm/pull/*/head
    [ $? -eq 0 ] || fatal "git fetch of pulls failed"
    [ -z "$AS_BRANCH" ] || git checkout $AS_BRANCH
    [ $? -eq 0 ] || fatal "git fetch of pull branch failed"
    [ -z "$AS_BRANCH" ] || echo "Using non-default AS_BRANCH: $AS_BRANCH"
  fi

  git fetch upstream
  echo "This is the JBoss-AS commit"
  echo $(git rev-parse upstream/main)
  echo "This is the AS_BRANCH $AS_BRANCH commit"
  echo $(git rev-parse HEAD)

  echo "Rebasing the wildfly upstream/main on top of the AS_BRANCH $AS_BRANCH"
  git pull --rebase upstream main
  [ $? -eq 0 ] || fatal "git rebase failed"

  if [ $REDUCE_SPACE = 1 ]; then
    echo "Deleting git dir to reduce disk usage"
    rm -rf .git
  fi

  WILDFLY_CLONED_REPO=$(pwd)
  cd $WORKSPACE
}

function build_as {
  echo "Building JBoss EAP/WildFly"

  cd $WILDFLY_CLONED_REPO

  WILDFLY_VERSION_FROM_JBOSS_AS=`awk '/wildfly-parent/ { while(!/<version>/) {getline;} print; }' ${WILDFLY_CLONED_REPO}/pom.xml | cut -d \< -f 2|cut -d \> -f 2`

  if [ ! -d  ${WILDFLY_CLONED_REPO}/dist/target/wildfly-${WILDFLY_VERSION_FROM_JBOSS_AS} ]; then

    # building WildFly
    export MAVEN_OPTS="-XX:MaxMetaspaceSize=512m $MAVEN_OPTS"
    JAVA_OPTS="-Xms1303m -Xmx1303m -XX:MaxMetaspaceSize=512m $JAVA_OPTS" ./build.sh clean install -B -DskipTests -Dts.smoke=false $IPV6_OPTS -Dversion.org.jboss.narayana.lra=${LRA_CURRENT_VERSION} "$@"
    [ $? -eq 0 ] || fatal "AS build failed"

  fi

  echo "AS version is ${WILDFLY_VERSION_FROM_JBOSS_AS}"
  JBOSS_HOME=${WILDFLY_CLONED_REPO}/dist/target/wildfly-${WILDFLY_VERSION_FROM_JBOSS_AS}
  export JBOSS_HOME=`echo  $JBOSS_HOME`

  # init files under JBOSS_HOME before AS TESTS is started
  init_jboss_home

  cd $WORKSPACE
}

function tests_as {
  # running WildFly testsuite if configured to be run by axis AS_TESTS

  cd $WILDFLY_CLONED_REPO
  JAVA_OPTS="-Xms1303m -Xmx1303m -XX:MaxMetaspaceSize=512m $JAVA_OPTS" ./build.sh clean install -B -DallTests $IPV6_OPTS -Dversion.org.jboss.narayana.lra=${LRA_CURRENT_VERSION} "$@"
  [ $? -eq 0 ] || fatal "AS tests failed"
  cd $WORKSPACE
}

function init_jboss_home {
  [ -d $JBOSS_HOME ] || fatal "missing AS - $JBOSS_HOME is not a directory"
  echo "JBOSS_HOME=$JBOSS_HOME"
  cp ${JBOSS_HOME}/docs/examples/configs/standalone-xts.xml ${JBOSS_HOME}/standalone/configuration
  cp ${JBOSS_HOME}/docs/examples/configs/standalone-rts.xml ${JBOSS_HOME}/standalone/configuration
  # configuring bigger connection timeout for jboss cli (WFLY-13385)
  CONF="${JBOSS_HOME}/bin/jboss-cli.xml"
  sed -e 's#^\(.*</jboss-cli>\)#<connection-timeout>30000</connection-timeout>\n\1#' "$CONF" > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
  grep 'connection-timeout' "${CONF}"
  #Enable remote debugger
  echo JAVA_OPTS='"$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,address=8787,server=y,suspend=n"' >> "$JBOSS_HOME"/bin/standalone.conf

  if [ $AS_TESTS_TRACE ]; then
    enable_as_trace "$JBOSS_HOME/standalone/configuration/standalone.xml"
    enable_as_trace "$JBOSS_HOME/standalone/configuration/standalone-full.xml"
  fi
}

function lra_as_tests {
  echo "#-1. LRA AS Tests"
  cd $WILDFLY_CLONED_REPO
  ./build.sh -f testsuite/integration/microprofile-tck/lra/pom.xml -fae -B -Dversion.org.jboss.narayana.lra=${LRA_CURRENT_VERSION} "$@" test
  [ $? -eq 0 ] || fatal "LRA AS Test failed"
  ./build.sh -f microprofile/lra/pom.xml -fae -B -Dversion.org.jboss.narayana.lra=${LRA_CURRENT_VERSION} "$@" test
  [ $? -eq 0 ] || fatal "LRA AS Test failed"
  cd ${WORKSPACE}
}

function lra_tests {
  echo "#0. LRA Test"
  echo "#0. Running LRA tests using $ARQ_PROF profile"
  # Ideally the following target would be test and integration-test but that doesn't seem to shutdown the server each time
  PRESERVE_WORKING_DIR=true ./build.sh -fae -B -P$ARQ_PROF $CODE_COVERAGE_ARGS $ENABLE_LRA_TRACE_LOGS -Dlra.test.timeout.factor="${LRA_TEST_TIMEOUT_FACTOR:-1.5}" "$@" install
  lra_arq=$?
  if [ $lra_arq != 0 ] ; then fatal "LRA Test failed with failures in $ARQ_PROF profile" ; fi
}



function enable_as_trace {
    CONF=${1:-"${JBOSS_HOME}/standalone/configuration/standalone-xts.xml"}
    echo "Enable trace logs for file '$CONF'"

    sed -e '/<logger category="com.arjuna">$/N;s/<logger category="com.arjuna">\n *<level name="WARN"\/>/<logger category="com.arjuna"><level name="TRACE"\/><\/logger><logger category="org.jboss.narayana"><level name="TRACE"\/><\/logger><logger category="org.jboss.jbossts"><level name="TRACE"\/><\/logger><logger category="org.jboss.jbossts.txbridge"><level name="TRACE"\/>/' $CONF > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
    sed -e '/<console-handler name="CONSOLE">$/N;s/<console-handler name="CONSOLE">\n *<level name="INFO"\/>/<console-handler name="CONSOLE"><level name="TRACE"\/>/' $CONF > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
}


function hw_spec {
  if [ -x /usr/sbin/system_profiler ]; then
    echo "sw_vers:"; sw_vers
    echo "system_profiler:"; /usr/sbin/system_profiler
  else
    set -o xtrace

    echo "uname -a"; uname -a
    echo "redhat release:"; cat /etc/redhat-release
    echo "java version:"; java -version
    echo "free:"; free -m
    echo "cpuinfo:"; cat /proc/cpuinfo
    echo "meminfo:"; cat /proc/meminfo
    echo "devices:"; cat /proc/devices
    echo "scsi:"; cat /proc/scsi/scsi
    echo "partitions:"; cat /proc/partitions

    echo "lspci:"; lspci
    echo "lsusb:"; lsusb
    echo "lsblk:"; lsblk
    echo "df:"; df
    echo "mount:"; mount | column -t | grep ext
  fi
}


function generate_code_coverage_report {
  echo "Generating code coverage report"
  cd ${WORKSPACE}
  ./build.sh -B -f code-coverage/pom.xml $CODE_COVERAGE_ARGS "$@" clean install
  [ $? -eq 0 ] || fatal "Code coverage report generation failed"
}

ulimit -a
ulimit -c unlimited
ulimit -a

initGithubVariables
check_if_pull_closed
check_if_pull_noci_label

init_test_options

# if QA_BUILD_ARGS is unset then get the db drivers form the file system otherwise get them from the
# default location (see build.xml). Note ${var+x} substitutes null for the parameter if var is undefined
[ -z "${QA_BUILD_ARGS+x}" ] && QA_BUILD_ARGS="-Ddriver.url=file:///home/jenkins/dbdrivers"

# Note: set QA_TARGET if you want to override the QA test ant target

# for IPv6 testing use export ARQ_PROF=arqIPv6
# if you don't want to run all the XTS tests set WSTX_MODULES to the ones you want, eg:
# export WSTX_MODULES="WSAS,WSCF,WSTX,WS-C,WS-T,xtstest,crash-recovery-tests"

[ -z "${WORKSPACE}" ] && fatal "UNSET WORKSPACE"

# FOR DEBUGGING SUBSEQUENT ISSUES
if [ -x /usr/bin/free ]; then
    /usr/bin/free
elif [ -x /usr/bin/vm_stat ]; then
    /usr/bin/vm_stat
else
    echo "Skipping memory report: no free or vm_stat"
fi

#Make sure no JBoss processes running
for i in `ps -eaf | grep java | grep "standalone.*.xml" | grep -v grep | cut -c10-15`; do kill -9 $i; done
#Make sure no processes from a previous test suite run is still running
MainClassPatterns="org.jboss.jbossts.qa com.arjuna.ats.arjuna.recovery.RecoveryManager"
kill_qa_suite_processes $MainClassPatterns

export MEM_SIZE=1024m
[ -z ${MAVEN_OPTS+x} ] && export MAVEN_OPTS="-Xms$MEM_SIZE -Xmx$MEM_SIZE"
export ANT_OPTS="-Xms$MEM_SIZE -Xmx$MEM_SIZE"
export EXTRA_QA_SYSTEM_PROPERTIES="-Xms$MEM_SIZE -Xmx$MEM_SIZE -XX:ParallelGCThreads=2"

# if we are building with IPv6 tell ant about it
export ANT_OPTS="$ANT_OPTS $IPV6_OPTS"

# run the job

[ $NARAYANA_BUILD = 1 ] && build_narayana "$@"
[ $AS_CLONE = 1 ] && clone_as "$@"
[ $AS_BUILD = 1 ] && build_as "$@"
[ $AS_TESTS = 1 ] && tests_as "$@"
[ $LRA_AS_TESTS = 1 ] && lra_as_tests "$@"
[ $LRA_TESTS = 1 ] && lra_tests "$@"
[ $PERF_TESTS = 1 ] && perf_tests "$@"
[ $CODE_COVERAGE = 1 ] && generate_code_coverage_report "$@"

if [[ -z $PROFILE ]]; then
    comment_on_pull "All tests passed - Job complete $BUILD_URL"
elif [[ $PROFILE == "PERF" ]]; then
    comment_on_pull "$PROFILE profile job finished $BUILD_URL"
else
    comment_on_pull "$PROFILE profile tests passed - Job complete $BUILD_URL"
fi

exit 0 # any failure would have resulted in fatal being called which exits with a value of 1
