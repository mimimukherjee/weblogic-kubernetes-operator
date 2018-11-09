#!/bin/bash
# Copyright 2017, 2018, Oracle Corporation and/or its affiliates. All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.

#
# This script introspects a WebLogic DOMAIN_HOME in order to generate:
#
#   - a description of the domain for the operator (domain name, cluster name, ports, etc)
#   - encrypted login files for accessing a NM
#   - encrypted boot.ini for booting WL 
#   - encrypted admin user password passed in via a plain-text secret (for use in sit config)
#   - situational config files for overriding the configuration within the DOMAIN_HOME
# 
# It works as part of the following flow:
#
#   (1) When an operator discovers a new domain, it launches this script via an
#       introspector k8s job.
#   (2) This script then:
#       (2A) Configures and starts a NM via startNodeManager.sh (in NODEMGR_HOME)
#       (2B) Calls introspectDomain.py, which depends on the NM
#       (2C) Exits 0 on success, non-zero otherwise.
#   (5) Operator parses the output of introspectDomain.py into files and:
#       (5A) Uses one to get the domain's name, cluster name, ports, etc.
#       (5B) Deploys a config map for the domain containing the files.
#   (6) Operator starts pods for domain's WebLogic servers.
#   (7) Pod 'startServer.sh' script loads files from the config map, 
#       copies/uses encrypted files, and applies sit config files.
#
# Prerequisites:
#
#    - Optionally set WL_HOME - default is /u01/oracle/wlserver.
#
#    - Optionally set MW_HOME - default is /u01/oracle.
#
#    - Transitively requires other env vars for startNodeManager.sh, wlst.sh,
#      and introspectDomain.py (see these scripts to find out what else needs to be set).
#

SCRIPTPATH="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"

function createFolder {
  # TBD was 777
  mkdir -m 750 -p $1
  if [ ! -d $1 ]; then
    trace "Unable to create folder $1"
    exit 1
  fi
}

createFolder $LOG_HOME
log_file=${LOG_HOME}/introspector-debug.log

echo "=============================" >> $log_file
echo "Beginning of introspectDomain" >> $log_file
echo "=============================" >> $log_file

# setup tracing

source ${SCRIPTPATH}/traceUtils.sh
[ $? -ne 0 ] && echo "Error: missing file ${SCRIPTPATH}/traceUtils.sh" && exit 1 

echo "traceUtils.sh found ok" >> $log_file

trace "Introspecting the domain"
echo "Introspecting the domain" >> $log_file

trace "Current environment:"
env

# set defaults

export WL_HOME=${WL_HOME:-/u01/oracle/wlserver}
export MW_HOME=${MW_HOME:-/u01/oracle}

# check if prereq env-vars, files, and directories exist

echo "Checking prereq env-vars" >> $log_file

checkEnv DOMAIN_UID \
         NAMESPACE \
         DOMAIN_HOME \
         JAVA_HOME \
         NODEMGR_HOME \
         LOG_HOME \
         WL_HOME \
         MW_HOME \
         || exit 1

for script_file in "${SCRIPTPATH}/wlst.sh" \
                   "${SCRIPTPATH}/startNodeManager.sh"  \
                   "${SCRIPTPATH}/introspectDomain.py"; do
  [ ! -f "$script_file" ] && trace "Error: missing file '${script_file}'." && exit 1 
done 

echo "prereq env-vars checked ok" >> $log_file

for dir_var in DOMAIN_HOME JAVA_HOME WL_HOME MW_HOME; do
  [ ! -d "${!dir_var}" ] && trace "Error: missing ${dir_var} directory '${!dir_var}'." && exit 1
done

# start node manager

trace "Starting node manager"
echo "Starting node manager" >> $log_file

${SCRIPTPATH}/startNodeManager.sh || exit 1

echo "node manager started" >> $log_file

# run instrospector wlst script

echo "Running introspector WLST script ${SCRIPTPATH}/introspectDomain.py" >> $log_file
trace "Running introspector WLST script ${SCRIPTPATH}/introspectDomain.py"

${SCRIPTPATH}/wlst.sh ${SCRIPTPATH}/introspectDomain.py || exit 1

trace "Domain introspection complete"
echo "Domain introspector complete" >> $log_file

exit 0
