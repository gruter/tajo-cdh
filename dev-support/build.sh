#!/usr/bin/env bash
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

set -e

PROTOBUF_VERSION=2.4.1

DIR=`dirname "$0"`
BUILD_HOME=`cd "$DIR/../"; pwd`

cd $BUILD_HOME/../
if [ -d "$BUILD_HOME/../protobuf-${PROTOBUF_VERSION}" ]; then
    rm -rf $BUILD_HOME/../protobuf-${PROTOBUF_VERSION}
fi

mkdir protobuf-${PROTOBUF_VERSION}
PROTO_HOME=`cd "protobuf-${PROTOBUF_VERSION}"; pwd`

cd $PROTO_HOME
echo "Fetching protocol buffer"
wget https://protobuf.googlecode.com/files/protobuf-${PROTOBUF_VERSION}.tar.gz
tar xzf protobuf-${PROTOBUF_VERSION}.tar.gz
rm protobuf-${PROTOBUF_VERSION}.tar.gz

cd $PROTO_HOME/protobuf-${PROTOBUF_VERSION}
./configure --prefix=$PROTO_HOME/protobuf-${PROTOBUF_VERSION}/build
make install
export PATH=$PROTO_HOME/protobuf-${PROTOBUF_VERSION}/build/bin:$PATH
alias protoc=$PROTO_HOME/protobuf-${PROTOBUF_VERSION}/build/bin/protoc

cd $BUILD_HOME
echo "Build Tajo"
mvn clean package -Pdist -Dtar
