Apache Tajo (incubation)
========================
Tajo is a relational and distributed data warehouse system for Hadoop.
Tajo is designed for low-latency and scalable ad-hoc queries, online
aggregation and ETL on large-data sets by leveraging advanced database
techniques. It supports SQL standards. It has its own query engine which
allows direct control of distributed execution and data flow. As a result,
Tajo has a variety of query evaluation strategies and more optimization
opportunities. In addition, Tajo will have a native columnar execution and
and its optimizer.

Project
=======
* Project Home (http://tajo.apache.org/)
* Source Repository (https://git-wip-us.apache.org/repos/asf/tajo.git)
* Issue Tracking (https://issues.apache.org/jira/browse/TAJO)

License
=======
* Apache License 2.0 (http://www.apache.org/licenses/LICENSE-2.0.html)

Documents
=========
* Tajo Wiki (http://wiki.apache.org/tajo)
* Getting Started (https://wiki.apache.org/tajo/GettingStarted)
* Build Instruction (https://wiki.apache.org/tajo/BuildInstruction)
* Query Language (https://wiki.apache.org/tajo/QueryLanguage)
* Configuration Guide (https://wiki.apache.org/tajo/Configuration)
* Backup and Restore Guide (https://wiki.apache.org/tajo/BackupAndRestore)
* Functions (https://wiki.apache.org/tajo/Functions)
* Tajo Interactive Shell (https://wiki.apache.org/tajo/tsql)

Requirements
============
* Unix System
* Java 1.6 or higher
* Hadoop 2.0.0-cdh4.3.0 or Hadoop 2.0.0-cdh4.4.0
* Protocol Buffers 2.4.1
* Maven 3.0 or higher
* Internet connection for first build (to fetch all Maven and Tajo dependencies)

Build
============
1. Maven build goals:
 * Clean                     : mvn clean
 * Compile                   : mvn compile
 * Run tests                 : mvn test
 * Run integrating tests     : mvn verify
 * Create JAR                : mvn package
 * Run findbugs              : mvn compile findbugs:findbugs
 * Install JAR in M2 cache   : mvn install
 * Build distribution        : mvn package [-Pdist][-Dtar]

2. Build options
 * Use -Dtar to create a TAR with the distribution (using -Pdist)
 * Use -Phadoop-cdh4.4.0 to apply on hadoop-2.0.0-cdh4.4.0
 * Use -Phadoop-cdh4.5.0 to apply on hadoop-2.0.0-cdh4.5.0
 * Use -Phadoop-cdh4.6.0 to apply on hadoop-2.0.0-cdh4.6.0
 * Use -Phcatalog-cdh4.3.0 to integrate with hive-0.10.0-cdh4.3.0
 * Use -Phcatalog-cdh4.4.0 to integrate with hive-0.10.0-cdh4.4.0
 * Use -Phcatalog-cdh4.5.0 to integrate with hive-0.10.0-cdh4.5.0
 * Use -Phcatalog-cdh4.6.0 to integrate with hive-0.10.0-cdh4.6.0

3. Tests options
 * Use -DskipTests to skip tests when running the following Maven goals:
    'package',  'install', 'deploy' or 'verify'
 * -Dtest=<TESTCLASSNAME>,<TESTCLASSNAME#METHODNAME>,....
 * -Dtest.exclude=<TESTCLASSNAME>
 * -Dtest.exclude.pattern=**/<TESTCLASSNAME1>.java,**/<TESTCLASSNAME2>.java

4. Building distributions
 * Create binary distribution for cdh4.3.0
 <pre>
  $ mvn package -Pdist -DskipTests -Dtar
 </pre>
 * Create binary distribution for cdh4.4.0
 <pre>
  $ mvn package -Pdist -DskipTests -Dtar -Phadoop-cdh4.4.0
 </pre>
 * Create binary distribution for cdh4.5.0
 <pre>
  $ mvn package -Pdist -DskipTests -Dtar -Phadoop-cdh4.5.0
 </pre>
 * Create binary distribution for cdh4.6.0
 <pre>
  $ mvn package -Pdist -DskipTests -Dtar -Phadoop-cdh4.6.0
 </pre>

Mailing lists
=============
* dev@tajo.incubator.apache.org - To discuss and ask development and usage
  issues. Send an empty email to dev-subscribe@tajo.incubator.apache.org in
  order to subscribe to this mailing list.

* commits@tajo.incubator.apache.org - In order to monitor commits to the source
  repository. Send an empty email to commits-subscribe@tajo.incubator.apache.org
  in order to subscribe to this mailing list.

To subscribe to the mailing lists, please send an email to:
${listname}-subscribe@tajo.incubator.apache.org. For example, to subscribe to 
dev, send an email from your desired subscription address to:
dev-subscribe@tajo.incubator.apache.org and follow the instructions from there.

Disclaimer
==========
Apache Tajo is an effort undergoing incubation at The Apache Software
Foundation (ASF) sponsored by the Apache Incubator PMC. Incubation is required
of all newly accepted projects until a further review indicates that the
infrastructure, communications, and decision making process have stabilized in
a manner consistent with other successful ASF projects. While incubation status
is not necessarily a reflection of the completeness or stability of the code,
it does indicate that the project has yet to be fully endorsed by the ASF.
