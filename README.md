# java-depend
Find dependencies between Java classes.

This is a small one-piece program for Java developers to find dependencies and
dependency cycles between their own classes.

Download the program Depend.java and compile it with
```
javac Depend.java
```
Run the program with
```
java Depend directory
```
The default directory is the current one.  The directory should contain compiled
class files. The program reads them to determine the dependencies among them. It
then prints the classes out in reverse dependency order, on the standard output.
Each class has its dependencies listed, and cyclic dependency groups among the
classes are reported.

The program is suitable only for very simple situations.  It does not handle
dependencies on library classes, or any other classes outside the given
directory, or in subdirectories.  It is based on the Java 8 class file format.
It is likely to work on earlier versions of Java, but unlikely to work on later
ones.  For more sophisticated requirements, see
[CDA](http://www.dependency-analyzer.org/) for example.
