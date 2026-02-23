# Javassist 패치 도구 검증 워크스루

이 문서는 `PatchWithJavassist.java` 도구의 검증 과정을 설명합니다.

## 1. 도구 준비
도구는 `tools/` 디렉토리에 위치하며, `libs/javassist.jar` 라이브러리를 사용합니다.

```bash
# 컴파일 (자동으로 run_patch.sh가 처리하지만 수동으로 할 경우)
javac -cp .:../libs/javassist.jar tools/PatchWithJavassist.java
```

## 2. 검증 단계
`Test.java` 파일을 사용하여 도구가 정상 작동하는지 확인했습니다.

1.  **테스트 클래스 생성 및 컴파일**:
    ```java
    public class Test {
        public static void main(String[] args) { ... }
        public static int add(int a, int b) { return a + b; }
    }
    ```
    ```bash
    javac Test.java
    ```

2.  **메소드 바이트코드 추출**:
    검증을 위해 헬퍼 스크립트를 사용하여 `add` 메소드(인덱스 1)의 바이트코드를 `Test_method_1.txt` 파일로 추출했습니다. (Hex String 포맷, `byteTok`과 유사한 형태 가정)

3.  **클래스 패치 (Javassist 사용)**:
    `run_patch.sh` 스크립트를 실행하여 `Test.class`의 메소드를 추출했던 바이트코드로 교체했습니다.
    ```bash
    ./tools/run_patch.sh verification/Test.class verification/Test_method_1.txt verification/Test_patched_javassist.class
    ```

4.  **실행 검증**:
    생성된 클래스 파일이 정상적으로 실행되는지 확인했습니다.
    ```bash
    mv verification/Test_patched_javassist.class verification/Test.class
    java -cp verification Test
    ```
    **결과**: 프로그램이 "Hello, World!"와 "Result: 15"를 출력하며 성공적으로 실행되었습니다.

## 3. 결론
Javassist를 사용하여 바이트코드 텍스트(Hex)를 원본 클래스에 안전하게 주입할 수 있음을 확인했습니다.
