import java.io.*;
import java.util.*;

/* Dependency checker. Free and open source: see licence.txt.

Find dependencies between the Java classes in a directory. Present the
results in reverse dependency order, emphasizing cyclic groups. Assumes the
Java 8 class file format.  */

// To make sure that only one class file is generated when the program is
// compiled, for maximum user convenience, the Depend class itself is used to
// create a separate object for each analyzed class, holding its information.
// The overall program data is passed around in method arguments.
class Depend {
    // The info associated with one class.
    private String fileName, className, classPath;
    private List<String> refs;
    private List<Depend> out, in;
    private boolean visited;
    private Set<Depend> group;

    public String toString() { return className; }

    public static void main(String[] args) {
        Depend program = new Depend();
        if (args.length >= 1 && (args.length > 1 || args[0].charAt(0) == '-')) {
            System.err.println("Use:  java Depend [directory]");
            System.exit(0);
        }
        program.run(args.length > 0 ? args[0] : ".");
    }

    // Find dependencies in the directory of classes.
    private void run(String folderName) {
        File folder = new File(folderName);
        List<Depend> nodes = findClasses(folder);
        for (Depend node : nodes) readClass(folder, node);
        buildRefs(nodes);
        reverseRefs(nodes);
        List<Set<Depend>> groups = findGroups(nodes);
        print(groups);
    }

    // Find the classes in the directory, excluding inner classes.
    private List<Depend> findClasses(File folder) {
        List<Depend> nodes = new ArrayList<Depend>();
        for (String name: folder.list()) {
            if (! name.endsWith(".class")) continue;
            if (name.contains("$")) continue;
            Depend node = new Depend();
            node.fileName = name;
            node.className = name.substring(0, name.length()-6);
            nodes.add(node);
        }
        return nodes;
    }

    // Read a class file, finding the class path, and extracting the references
    // to other classes from the constant pool.
    void readClass(File folder, Depend node) {
        try {
            InputStream is = new FileInputStream(new File(folder, node.fileName));
            DataInputStream in = new DataInputStream(is);
            skipHeader(in);
            String[] strings = readConstantPool(node, in);
            skipSuperClasses(in);
            readFields(node, in, strings);
            readMethods(node, in, strings);
            readAttributes(node, in, strings);
            in.close();
        }
        catch (Exception e) { throw new Error(e); }
    }

    // Skip the magic number, and minor/major version numbers.
    void skipHeader(DataInputStream in) throws Exception {
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
    }

    // Define the codes used in class files for types of constant pool entry.
    private static final int
	    UTF8 = 1, INTEGER = 3, FLOAT = 4, LONG = 5, DOUBLE = 6, CLASS = 7,
	    STRING = 8, FIELDREF = 9, METHODREF = 10, INTERFACE_METHODREF = 11,
	    NAME_AND_TYPE = 12, METHOD_HANDLE = 15, METHOD_TYPE = 16,
        INVOKE_DYNAMIC = 18;

    // Read the constant pool, skip access flags, read class name.
    // Extract the unique class references, return the strings.
    String[] readConstantPool(Depend node, DataInputStream in) throws Exception {
        int nConstants = in.readUnsignedShort();
        String[] strings = new String[nConstants];
        int[] classes = new int[nConstants];
        for (int i = 1; i < nConstants; i++) {
            int type = in.readUnsignedByte();
            switch (type) {
            case UTF8: strings[i] = in.readUTF(); break;
            case CLASS: classes[i] = in.readUnsignedShort(); break;
            case FIELDREF: case METHODREF: case INTERFACE_METHODREF:
            case NAME_AND_TYPE: case INVOKE_DYNAMIC:
                in.readUnsignedShort();
                in.readUnsignedShort();
                break;
            case STRING: case METHOD_TYPE: in.readUnsignedShort(); break;
            case INTEGER: in.readInt(); break;
            case FLOAT: in.readFloat(); break;
            case LONG: in.readLong(); i++; break;
            case DOUBLE: in.readDouble(); i++; break;
            case METHOD_HANDLE:
                in.readUnsignedByte();
                in.readUnsignedShort();
                break;
            }
        }
        int access = in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        node.classPath = strings[classes[thisClass]];
        node.refs = new ArrayList<String>();
        for (int i=0; i<classes.length; i++) {
            if (classes[i] == 0) continue;
            String name = strings[classes[i]];
            if (name.contains("$")) continue;
            if (name.equals(node.classPath)) continue;
            if (node.refs.contains(name)) continue;
            node.refs.add(name);
        }
        return strings;
    }

    // Skip the superclass and super interfaces.  They have already been picked
    // up as class entries in the constant pool.
    void skipSuperClasses(DataInputStream in) throws Exception {
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) in.readUnsignedShort();
    }

    // Read the fields.  Extract class references from descriptors.
    void readFields(Depend node, DataInputStream in, String[] strings)
    throws Exception {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            int access = in.readUnsignedShort();
            int name = in.readUnsignedShort();
            int descriptorId = in.readUnsignedShort();
            readDescriptor(node, strings[descriptorId]);
            readAttributes(node, in, strings);
        }
    }

    // Read the methods in the same way as the fields.
    void readMethods(Depend node, DataInputStream in, String[] strings)
    throws Exception {
        readFields(node, in, strings);
    }

    // Read field, method or class attributes, extract signatures.
    void readAttributes(Depend node, DataInputStream in, String[] strings)
    throws Exception {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            int t = in.readUnsignedShort();
            int len = in.readInt();
            if (strings[t].equals("Signature") && len == 2) {
                int si = in.readUnsignedShort();
                String s = strings[si];
                readDescriptor(node, s);
            }
            else for (int j = 0; j < len; j++) in.readUnsignedByte();
        }
    }

    // Find class references of the form L...; or L...< in the descriptor.
    void readDescriptor(Depend node, String descriptor) {
        while (true) {
            int L = descriptor.indexOf('L');
            if (L < 0) break;
            int S = descriptor.indexOf(';', L);
            int B = descriptor.indexOf('<', L);
            if (B >= 0 && B < S) S = B;
            String name = descriptor.substring(L+1, S);
            descriptor = descriptor.substring(S+1);
            if (name.contains("$")) continue;
            if (name.equals(node.classPath)) continue;
            if (node.refs.contains(name)) continue;
            node.refs.add(name);
        }
    }

    // Build a map from classPath to node.  Use it to convert the refs into
    // direct node pointers.  Discard refs to classes outside the folder.
    void buildRefs(List<Depend> nodes) {
        Map<String,Depend> map = new HashMap<String,Depend>();
        for (Depend node : nodes) map.put(node.classPath, node);
        for (Depend node : nodes) {
            node.out = new ArrayList<Depend>();
            for (String ref : node.refs) {
                Depend to = map.get(ref);
                if (to != null) node.out.add(to);
            }
        }
    }

    // Find the refs from other nodes into each node.
    void reverseRefs(List<Depend> nodes) {
        for (Depend node : nodes) node.in = new ArrayList<Depend>();
        for (Depend node : nodes) {
            for (Depend to : node.out) to.in.add(node);
        }
    }

    // Find the cyclically dependent groups using Kosaraju's algorithm.
    // See https://en.wikipedia.org/wiki/Kosaraju's_algorithm
    private List<Set<Depend>> findGroups(List<Depend> nodes) {
        List<Set<Depend>> groups = new ArrayList<Set<Depend>>();
        List<Depend> list = new ArrayList<Depend>();
        for (Depend node : nodes) node.visited = false;
        for (Depend node : nodes) node.group = null;
        for (Depend node : nodes) visit(list, node);
        for (Depend node : list) assign(groups, node, node);
        return groups;
    }

    // Visit a node during the algorithm.
    private void visit(List<Depend> list, Depend node) {
        if (node.visited) return;
        node.visited = true;
        for (Depend to : node.out) visit(list, to);
        list.add(0, node);
    }

    // Assign a node to a group during the algorithm.
    private void assign(List<Set<Depend>> groups, Depend node, Depend root) {
        if (node.group != null) return;
        if (node == root) {
            root.group = new HashSet<Depend>();
            groups.add(root.group);
        }
        root.group.add(node);
        node.group = root.group;
        for (Depend from : node.in) assign(groups, from, root);
    }

    void print(List<Set<Depend>> groups) {
        for (Set<Depend> g : groups) {
            if (g.size() == 1) {
                Depend node = g.iterator().next();
                System.out.println(node + " -> " + node.out);
            } else {
                System.out.println(g);
                for (Depend node : g) {
                    System.out.println("    " + node + " -> " + node.out);
                }
            }
        }
    }

    // Testing removed.
}
