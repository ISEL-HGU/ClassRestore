# ClassRestore: Javassist & ASM 기반 클래스 패치 도구

사용자께서 제안하신 **byteTok -> byteT5 -> Javassist Patch -> ASM Frame Compute** 파이프라인을 구현한 도구입니다.

## 1. 도구 개요
이 도구(`PatchWithJavassist`)는 T5 모델이 생성한 16진수 바이트코드 문자열(Hex String)을 원본 클래스에 주입하고, **ASM을 사용하여 스택 프레임(Stack Map Table)을 자동으로 재계산**합니다.

### 주요 기능
1.  **ASM 프레임 재계산**: `COMPUTE_FRAMES` 옵션으로 복잡한 스택 맵 테이블을 자동 생성하여 `VerifyError` 방지.
2.  **CP Remapping (New)**: 패치 데이터가 다른 빌드 환경(Constant Pool)에서 생성된 경우, `Reference Class`를 통해 자동으로 인덱스를 매핑하여 주입.
3.  **자동 무결성 검증 (New)**: 패치 완료 후 `javap`를 자동으로 실행하여 생성된 클래스 파일의 구조적 무결성을 즉시 검증.

*   **위치**: `/data2/seungwook/ClassRestore/tools/PatchWithJavassist.java`
*   **실행 스크립트**: `/data2/seungwook/ClassRestore/tools/run_patch.sh`
*   **라이브러리**: `javassist.jar`, `asm.jar`

## 2. 사용 방법 (파이프라인)

### 단계 1: 텍스트 추출 (byteTok)
원본 클래스에서 취약한 메소드를 텍스트(Hex 등) 형태로 추출합니다.

### 단계 2: 모델 인퍼런스 (byteT5)
추출된 텍스트를 T5 모델에 입력하여 패치된 바이트코드 텍스트(`.txt`)를 얻습니다.

### 단계 3: 클래스 패치 및 검증 (Javassist + ASM)

`run_patch.sh` 스크립트를 사용하여 다음 작업을 한 번에 수행합니다:
1.  **Patch Injection**: Hex 코드를 원본 클래스에 삽입 (필요 시 CP Remapping 수행).
2.  **Frame Recomputation**: ASM을 이용해 스택 프레임 재계산.
3.  **Verification**: `javap`를 이용해 출력 파일 무결성 검증.

```bash
# 사용법: ./run_patch.sh <원본클래스> <패치된TXT> <출력클래스> [ReferenceClass]
cd /home/chahyang/.gemini/antigravity/brain/3f6e056a-bd5b-4578-a1ea-b34e1aabdd34
./run_patch.sh Original.class patched_method.txt Patched.class Reference.class
```

#### 옵션 설명
*   **Reference Class (선택)**: 패치 데이터가 생성될 때 기준이 된 클래스 파일.
    *   입력 시: **CP Remapping 모드**로 동작 (패치 데이터의 인덱스를 원본 클래스에 맞게 변환).
    *   미입력 시: 패치 데이터 인덱스가 원본 클래스와 일치한다고 가정.

#### 실행 결과 메시지
*   `[SUCCESS]`: 패치 및 프레임 재계산 완료. 생성된 파일 경로 출력.
*   `[VERIFIED]`: `javap` 검사 통과. 파일이 구조적으로 유효함.
*   `[FAILURE]`: 패치 과정 중 오류 발생 (예: CP 불일치).
*   `[ERROR]`: 파일은 생성되었으나 `javap` 검사 실패 (파일 깨짐).

### [2026-02-23] 다중 순차 패치 시 상수풀(CP) 인덱스 꼬임 문제 해결
*   **문제 상황**: 한 클래스 파일에 여러 번 연속으로 패치를 적용할 경우 (`--diff` 또는 기본 모드), 두 번째 패치부터 `ClassCastException` 이나 `VerifyError` 가 발생하는 치명적인 버그가 있었습니다.
*   **원인**: ASM의 `ClassWriter(COMPUTE_FRAMES)` 옵션이 스택 프레임을 재계산하면서 기존의 상수풀(Constant Pool) 구조를 완전히 갈아엎고 최적화하여 인덱스를 새로 매기기 때문이었습니다. 이로 인해 두 번째 패치 시, 기존 텍스트 파일(buggy)이 기억하고 있던 원본 상수풀 인덱스 번호가 뒤섞인 대상 클래스(Patched)의 인덱스와 더 이상 일치하지 않게 되었습니다.
*   **해결 방법**: `ClassReader` 객체를 `ClassWriter` 생성자에 함께 인자로 넘겨주어(`new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES)`), ASM이 기존 상수풀 구조를 파괴하지 않고 완벽하게 보존(Preserve)하면서 오직 새 프레임 정보와 추가된 상수들만 맨 뒤에 이어붙이도록 `PatchWithJavassist.java` 로직을 수정했습니다.
*   **결과**: 이제 원본 클래스뿐만 아니라 이미 기능이 패치된 클래스 파일을 대상으로도 몇 번이고 안정적으로 추가 패치를 수행하여 기능을 누적할 수 있습니다.

### [2026-02-23] 클래스 초기화 정적 블록(`<clinit>`) 패치 불가 문제 해결
*   **문제 상황**: `static { ... }` 으로 선언된 정적 초기화 블록에 대한 패치를 시도할 경우, `PatchWithJavassist`가 해당 메소드를 찾지 못하고 `javassist.NotFoundException: <clinit>(..) is not found` 에러를 발생시키며 중단되었습니다.
*   **원인**: Javassist의 일반적인 함수 검색 메서드인 `getMethod()`는 `<init>`(생성자)와 더불어 `<clinit>`(정적 블록)과 같은 특수 컴파일러 생성 함수를 식별하지 않기 때문입니다.
*   **해결 방법**: 패치 대상 메소드의 이름이 `<clinit>`인 경우를 명시적으로 식별하여 일반 `getMethod()` 대신 `getClassInitializer()` 메서드를 사용하여 클래스 초기화 블록을 정확히 불러와 교체하도록 `PatchWithJavassist.java`의 로직 분기문을 추가 수정했습니다.
*   **결과**: 이제 T5 모델이 예측한 패치 데이터가 일반 함수, 생성자, 혹은 정적 초기화 블록(Static Block)이더라도 모두 완벽하게 식별하여 차분 패치 및 프레임 재계산을 수행할 수 있습니다.

## 3. 작동 원리 (내부 로직)
1.  **Hex 파싱**: 텍스트 파일의 바이트코드를 바이트 배열로 변환합니다.
2.  **Javassist 주입**:
    *   `Reference Class` 유무에 따라 `MethodInfo`를 생성하고, `CTMethod.copy` 등을 통해 원본 클래스로 이식합니다.
3.  **ASM 프레임 재계산**:
    *   `ASM ClassWriter(COMPUTE_FRAMES)`옵션을 사용하여 스택 맵 프레임을 재계산합니다.
4.  **검증**:
    *   생성된 파일을 `javap -p`로 실행하여 Exit Code를 확인합니다.
