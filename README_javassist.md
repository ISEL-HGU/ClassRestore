# ClassRestore: Javassist & ASM 기반 클래스 패치 도구

## 1. 도구 개요
이 도구(`PatchWithJavassist`)는 T5 모델이 생성한 16진수 바이트코드 문자열(Hex String)을 원본 클래스에 주입하고, **ASM을 사용하여 스택 프레임(Stack Map Table)을 자동으로 재계산**합니다.

### 주요 기능
1.  **ASM 프레임 재계산**: `COMPUTE_FRAMES` 옵션으로 복잡한 스택 맵 테이블을 자동 생성하여 `VerifyError` 방지.
2.  **CP Remapping**: 패치 데이터가 다른 빌드 환경(Constant Pool)에서 생성된 경우, `Reference Class`를 통해 자동으로 인덱스를 매핑하여 주입.
3.  **차분 패치 (Differential Patching)**: 대상 클래스의 상수풀을 100% 보존하면서 오직 바이트코드(`CodeAttribute`) 부분만 안전하게 교체하는 고급 기능 (`--diff` 옵션) 지원.
4.  **자동 무결성 검증**: 패치 완료 후 `javap`를 자동으로 실행하여 생성된 클래스 파일의 구조적 무결성을 즉시 검증.
5.  **다중 순차 패치 (Sequential Patching)**: ASM 최적화를 통해 상수풀(CP) 인덱스를 영구 보존하여, 한 클래스에 여러 번 연속으로 패치를 적용해도 인덱스가 깨지지 않습니다.

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
4.  **Auto Save**: 결과물은 자동으로 `ClassRestore/output/` 디렉토리에 저장됩니다.

```bash
# 사용법: ./run_patch.sh <원본클래스> <패치된TXT> <출력파일명> [--ref ReferenceClass | --diff OriginalHexFile]
cd /data2/seungwook/ClassRestore/tools

# 모드 1: 차분 패치 (가장 권장) - 원본의 상수풀을 100% 유지하면서 코드부만 교체
./run_patch.sh Original.class patched_method.txt Patched.class --diff original_method.txt

# 모드 2: CP 리매핑 - 다른 클래스의 상수풀을 기준으로 작성된 패치를 원본에 맞게 변환
./run_patch.sh Original.class patched_method.txt Patched.class --ref Reference.class

# 모드 3: 직접 주입 - 패치 바이트코드가 원본 상수풀 인덱스와 완벽히 일치한다고 가정
./run_patch.sh Original.class patched_method.txt Patched.class
```
#### 동작 원리
위 명령어들을 실행하면,
1. Original.class (원본)의 특정 메소드를 패치하여,
2. Patched.class라는 이름으로 결과 파일을 `../output/` 에 생성합니다.

#### 주요 옵션 설명 (`[--ref]` vs `[--diff]`)
*   **`--diff OriginalHexFile` (차분 패치 - MODE C)**:
    *   원본 바이트코드의 텍스트 포맷을 함께 제공하여, 오직 실질적인 로직(`CodeAttribute`) 부분만 잘라내어 원본에 붙여넣습니다. 참조 외부 클래스(Reference Class)를 구할 수 없을 때 가장 안정적인 패치(Constant Pool 엉킴 방지)를 보장합니다.
*   **`--ref ReferenceClass` (CP Remapping - MODE A)**:
    *   패치 데이터가 생성될 때 기준이 된 컴파일된 원본 클래스. 패치 데이터 내의 상수 인덱스를 Target 클래스에 맞게 자동으로 변환하여 주입합니다.
*   옵션이 없는 경우 **(Direct Injection - MODE B)**:
    *   모델이 생성한 패치 데이터의 인덱스 번호가 타겟 클래스 파일의 인덱스와 100% 동일하다고 가정하고 조건 없이 그대로 덮어씁니다.

#### 실행 결과 메시지
*   `[SUCCESS]`: 패치 및 프레임 재계산 완료. 생성된 파일 경로 출력.
*   `[VERIFIED]`: `javap` 검사 통과. 파일이 구조적으로 유효함.
*   `[FAILURE]`: 패치 과정 중 오류 발생 (예: CP 불일치).
*   `[ERROR]`: 파일은 생성되었으나 `javap` 검사 실패 (파일 깨짐).

## 3. 작동 원리 (내부 로직)
1.  **Hex 파싱**: 텍스트 파일의 바이트코드를 바이트 배열로 변환합니다.
2.  **Javassist 주입**:
    *   `Reference Class` 유무에 따라 `MethodInfo`를 생성하고, `CTMethod.copy` 등을 통해 원본 클래스로 이식합니다.
3.  **ASM 프레임 재계산**:
    *   `ASM ClassWriter(COMPUTE_FRAMES)`옵션을 사용하여 스택 맵 프레임을 재계산합니다.
4.  **검증**:
    *   생성된 파일을 `javap -p`로 실행하여 Exit Code를 확인합니다.

## 5. 기술적 상세 (Why & How)

### 5.1 왜 Reference Constant Pool이 필요한가?
Java 바이트코드 명령어는 메소드나 필드를 지칭할 때 **이름이 아닌 "번호(Index)"를 사용**합니다.

*   **상황**: 패치 데이터(Hex)에 `b6 00 0F` (`invokevirtual #15`)라는 명령어가 있다고 가정합시다.
*   **문제**: 이 `#15`가 무엇을 의미하는지는 **이 코드가 만들어진 환경(Reference Class)의 Constant Pool(대조표)**을 봐야만 알 수 있습니다.
    *   **Reference Class**: `#15` = `println()` 메소드 (정상)
    *   **Target Class**: `#15` = `"Hello"` 문자열 (전혀 다름)
*   **결과**: Reference 없이 이 코드를 Target에 그대로 덮어쓰면, JVM은 `"Hello"` 문자열을 함수처럼 실행하려다 에러(`VerifyError` 또는 `ClassCastException`)를 냅니다.
*   **해결책 (CP Remapping)**: 
    1.  도구가 **Reference CP**를 보고 `#15`가 `println()`임을 알아냅니다.
    2.  **Target CP**에서 `println()`이 몇 번인지(예: `#99`) 찾거나 새로 만듭니다.
    3.  명령어를 `b6 00 63` (`invokevirtual #99`)으로 **수정(Remapping)**하여 주입합니다.

### 5.2 Javassist와 ASM의 역할 분담

| 도구 | 역할 | 구체적인 동작 원리 |
| :--- | :--- | :--- |
| **Javassist** | **Translator & Surgeon** (이식 및 번역) | 1. **Hex 파싱**: Reference CP를 이용해 Hex 코드를 `MethodInfo` 객체로 변환합니다.<br>2. **CP Remapping**: `CtMethod.copy()`를 수행할 때, Reference CP의 모든 인덱스를 Target CP에 맞는 인덱스로 **자동 변환**합니다. (가장 핵심적인 역할)<br>3. **Injection**: 기존 메소드를 제거하고 변환된 새 메소드를 Target 클래스 구조에 삽입합니다. |
| **ASM** | **Inspector & Certifier** (검증 및 마감) | 1. **Control Flow Analysis**: Javassist가 수정한 클래스를 다시 읽어서, 모든 분기(Branch)와 점프 명령어를 분석합니다.<br>2. **StackMapTable 생성**: Java 7+ JVM은 검증을 위해 "각 시점의 스택 상태"를 기록한 `StackMapTable`을 요구합니다. Javassist는 이를 완벽하게 생성하지 못하는 경우가 많습니다.<br>3. **Re-calculation**: `COMPUTE_FRAMES` 옵션을 통해 올바른 스택 맵을 처음부터 다시 계산해서 넣어줍니다. 이 과정이 없으면 JVM이 클래스를 로드 거부할 수 있습니다. |

## 6. 트러블슈팅 및 업데이트 내역

### [2026-02-23] 다중 순차 패치 시 상수풀(CP) 인덱스 꼬임 문제 해결
*   **문제 상황**: 한 클래스 파일에 여러 번 연속으로 패치를 적용할 경우 (`--diff` 또는 기본 모드), 두 번째 패치부터 `ClassCastException` 이나 `VerifyError` 가 발생하는 치명적인 버그가 있었습니다.
*   **원인**: ASM의 `ClassWriter(COMPUTE_FRAMES)` 옵션이 스택 프레임을 재계산하면서 기존의 상수풀(Constant Pool) 구조를 완전히 갈아엎고 최적화하여 인덱스를 새로 매기기 때문이었습니다. 이로 인해 두 번째 패치 시, 기존 텍스트 파일(buggy)이 기억하고 있던 원본 상수풀 인덱스 번호가 뒤섞인 대상 클래스(Patched)의 인덱스와 더 이상 일치하지 않게 되었습니다.
*   **해결 방법**: `ClassReader` 객체를 `ClassWriter` 생성자에 함께 인자로 넘겨주어(`new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES)`), ASM이 기존 상수풀 구조를 파괴하지 않고 완벽하게 보존(Preserve)하면서 오직 새 프레임 정보와 추가된 상수들만 맨 뒤에 이어붙이도록 `PatchWithJavassist.java` 로직을 수정했습니다.
*   **결과**: 이제 원본 클래스뿐만 아니라 이미 기능이 패치된 클래스 파일을 대상으로도 몇 번이고 안정적으로 추가 패치를 수행하여 기능을 누적할 수 있습니다.
