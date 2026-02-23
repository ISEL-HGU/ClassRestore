public class Test {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
        int a = 5;
        int b = 10;
        int c = add(a, b);
        System.out.println("Result: " + c);
    }

    public static int add(int a, int b) {
        return a + b;
    }
}
