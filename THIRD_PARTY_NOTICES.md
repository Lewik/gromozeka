# Third-Party Notices

## Eclipse Temurin

Gromozeka self-contained distributions include an unmodified Eclipse Temurin
21 JRE. OpenJDK code is licensed under GNU GPL version 2 with the Classpath
Exception and Assembly Exception. The complete runtime legal tree is preserved
under `runtime/java`. Release identity, source, and license information are
available from:

- https://adoptium.net/temurin/releases/
- https://github.com/adoptium/temurin21-binaries
- https://openjdk.org/legal/

## Node.js

Gromozeka Worker distributions include an unmodified official Node.js binary.
Node.js is licensed under the MIT License and includes components under the
additional licenses collected in its distribution. The complete upstream
`LICENSE` is preserved under `runtime/node/LICENSE`. Source and license
information are available from:

- https://nodejs.org/dist/
- https://github.com/nodejs/node
- https://github.com/nodejs/node/blob/main/LICENSE

## pty4j

Gromozeka distributes the unmodified `org.jetbrains.pty4j:pty4j` library for
portable pseudo-terminal support. pty4j is licensed under the Eclipse Public
License 1.0. The source corresponding to the distributed `0.13.12` artifact,
its license, and upstream notices are available from:

- https://github.com/JetBrains/pty4j/tree/b7554d649f6374183040e6554942b6d5b028b5e5
- https://www.eclipse.org/legal/epl-v10.html

On behalf of all pty4j contributors, all warranties and conditions, express
and implied, are disclaimed, including title, non-infringement,
merchantability, and fitness for a particular purpose. All contributor
liability for damages is excluded, including direct, indirect, special,
incidental, and consequential damages such as lost profits. Any terms that
differ from the Eclipse Public License are offered by Gromozeka alone, not by
pty4j contributors.

Upstream pty4j notice:

> This library is based on elt - Local Terminal Plug-In for Eclipse (Eclipse
> Public License) and JPty - A small PTY interface for Java (Apache Software
> License), Copyright (c) 2012 J.W. Janssen.
>
> This library includes software (JNA) developed by Timothy Wall and others.
> Copyright (C) 2012, Timothy Wall / JNA community.

## Java Native Access

Gromozeka uses the unmodified `net.java.dev.jna:jna` library to query native
operating-system capabilities. JNA is distributed under the Apache License 2.0
option offered by its dual-license terms. Source and license texts for the
distributed `5.14.0` artifact are available from:

- https://github.com/java-native-access/jna/tree/5.14.0
- https://www.apache.org/licenses/LICENSE-2.0.txt
