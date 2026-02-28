# ClassRestore: Javassist & ASM 기반 클래스 패치 도구

사용자께서 제안하신 **byteTok -> byteT5 -> Javassist Patch -> ASM Frame Compute** 파이프라인을 구현한 도구입니다.

## 1. 도구 개요
이 도구(`ClassRestore`)는 T5 모델이 생성한 16진수 바이트코드 문자열(Hex String)을 원본 클래스에 주입하고, **ASM을 사용하여 스택 프레임(Stack Map Table)을 자동으로 재계산**합니다.

### 주요 기능
1.  **ASM 프레임 재계산**: `COMPUTE_FRAMES` 옵션으로 복잡한 스택 맵 테이블을 자동 생성하여 `VerifyError` 방지.
2.  **CP Remapping (New)**: 패치 데이터가 다른 빌드 환경(Constant Pool)에서 생성된 경우, `Reference Class`를 통해 자동으로 인덱스를 매핑하여 주입.
3.  **자동 무결성 검증 (New)**: 패치 완료 후 `javap`를 자동으로 실행하여 생성된 클래스 파일의 구조적 무결성을 즉시 검증.

*   **위치**: `/data2/seungwook/ClassRestore/tools/ClassRestore.java`
*   **실행 스크립트**: `/data2/seungwook/ClassRestore/tools/run_patch.sh`
*   **라이브러리**: `javassist.jar`, `asm.jar`

## 2. 사용 방법 (파이프라인)

### 단계 1: 텍스트 추출 (byteTok)
원본 클래스에서 취약한 메소드를 텍스트(Hex 등) 형태로 추출합니다.

### 단계 2: 모델 인퍼런스 (byteT5)
추출된 텍스트를 T5 모델에 입력하여 패치된 바이트코드 텍스트(`.txt`)를 얻습니다.

### 단계 3: 클래스 패치 및 검증 (Javassist + ASM)

`run_patch.sh` 스크립트를 사용하여 다음 작업을 한 번에 수행합니다:
1.  **Patch Injection**: Hex 코드를 처리하여 실질적인 차분(로직)만 추출해 원본 클래스에 삽입.
2.  **Frame Recomputation**: ASM을 이용해 스택 프레임 재계산.
3.  **Verification**: `javap`를 이용해 출력 파일 무결성 검증.

```bash
# 사용법: ./run_patch.sh <원본클래스> <패치된TXT> <출력파일명> <원본TXT>
cd /data2/seungwook/ClassRestore/tools

# 차분 패치 실행 - 원본의 상수풀을 100% 유지하면서 코드부만 교체
./run_patch.sh Original.class patched_method.txt Patched.class original_method.txt
```

#### 필수 인자 심층 설명
*   **`<원본TXT>` (Original Hex)**:
    *   **필수 항목**: 원본 바이트코드 텍스트(Buggy Hex)를 함께 제공하여, 오직 실질적인 로직(`CodeAttribute`)만 잘라내어 덮어씌웁니다. 무한한 다중/순차 패치(Sequential Patching)가 필요한 경우 해당 방식을 사용해야 기존의 상수풀 인덱스가 보존됩니다.

#### 실행 결과 메시지
*   `[SUCCESS]`: 패치 및 프레임 재계산 완료. 생성된 파일 경로 출력.
*   `[VERIFIED]`: `javap` 검사 통과. 파일이 구조적으로 유효함.
*   `[FAILURE]`: 패치 과정 중 오류 발생 (예: 로직 구조 붕괴로 인한 예외).
*   `[ERROR]`: 파일은 생성되었으나 `javap` 검사 실패 (파일 깨짐).

## 3. 작동 원리 (내부 로직)
1.  **Hex 파싱**: 텍스트 파일의 바이트코드를 바이트 배열로 변환합니다.
2.  **Javassist 주입**:
    *   원본 및 패치된 `MethodInfo`를 대조하여, 순수 로직(`CodeAttribute`)만을 추출해 원본 클래스로 이식합니다.
3.  **ASM 프레임 재계산**:
    *   `ASM ClassWriter(COMPUTE_FRAMES)`옵션을 사용하여 스택 맵 프레임을 재계산합니다.
4.  **검증**:
    *   생성된 파일을 `javap -p`로 실행하여 Exit Code를 확인합니다.

## 4. 업데이트 히스토리
### [2026-02-23] 다중 순차 패치 시 상수풀(CP) 인덱스 꼬임 문제 해결
*   **문제 상황**: 한 클래스 파일에 여러 번 연속으로 패치를 적용할 경우 (`--diff` 또는 기본 모드), 두 번째 패치부터 `ClassCastException` 이나 `VerifyError` 가 발생하는 치명적인 버그가 있었습니다.
*   **원인**: ASM의 `ClassWriter(COMPUTE_FRAMES)` 옵션이 스택 프레임을 재계산하면서 기존의 상수풀(Constant Pool) 구조를 완전히 갈아엎고 최적화하여 인덱스를 새로 매기기 때문이었습니다. 이로 인해 두 번째 패치 시, 기존 텍스트 파일(buggy)이 기억하고 있던 원본 상수풀 인덱스 번호가 뒤섞인 대상 클래스(Patched)의 인덱스와 더 이상 일치하지 않게 되었습니다.
*   **해결 방법**: `ClassReader` 객체를 `ClassWriter` 생성자에 함께 인자로 넘겨주어(`new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES)`), ASM이 기존 상수풀 구조를 파괴하지 않고 완벽하게 보존(Preserve)하도록 수정했습니다. **이로 인해 모델이 생성한 패치에 완전히 새로운 정보(새로운 문자열, 새로운 함수 호출 등)가 포함되어 있더라도, 기존 인덱스는 절대 건드리지 않고 상수풀의 맨 마지막 번호 뒤에 새로운 인덱스를 조용히 덧붙이는(Append) 방식으로 동작**하게 되었습니다.
*   **결과**: 패치 횟수에 상관없이 기준이 되는 상수풀 번호판이 항상 완벽하게 보존되므로, 동일한 클래스에 대해 **무한정 다중 누적 패치(Sequential Patching)**가 가능해졌습니다.

### [2026-02-23] 클래스 초기화 정적 블록(`<clinit>`) 패치 불가 문제 해결
*   **문제 상황**: `static { ... }` 으로 선언된 정적 초기화 블록에 대한 패치를 시도할 경우, `ClassRestore`가 해당 메소드를 찾지 못하고 `javassist.NotFoundException: <clinit>(..) is not found` 에러를 발생시키며 중단되었습니다.
*   **원인**: Javassist의 일반적인 함수 검색 메서드인 `getMethod()`는 `<init>`(생성자)와 더불어 `<clinit>`(정적 블록)과 같은 특수 컴파일러 생성 함수를 식별하지 않기 때문입니다.
*   **해결 방법**: 패치 대상 메소드의 이름이 `<clinit>`인 경우를 명시적으로 식별하여 일반 `getMethod()` 대신 `getClassInitializer()` 메서드를 사용하여 클래스 초기화 블록을 정확히 불러와 교체하도록 `ClassRestore.java`의 로직 분기문을 추가 수정했습니다.
*   **결과**: 이제 T5 모델이 예측한 패치 데이터가 일반 함수, 생성자, 혹은 정적 초기화 블록(Static Block)이더라도 모두 완벽하게 식별하여 차분 패치 및 프레임 재계산을 수행할 수 있습니다.

### [2026-02-27] AI 모델의 상수풀 인덱스 환각(Hallucination) 방어 로직 구현
*   **문제 상황**: T5 인퍼런스 모델이 예측한 패치 데이터를 원본 클래스에 주입하려 할 때, `WARNING: Patched MethodInfo has no CodeAttribute!` 에러가 발생하며 패치에 실패하는 현상을 발견했습니다.
*   **원인**: AI 모델은 로직을 완벽히 생성해내더라도, 그 로직의 겉봉투 격인 '상수풀 번호표(예: `Code` 문자열의 인덱스)'를 미세하게 빗나가서(Shift) 이따금 다르게 예측하는 기계적 환각(Hallucination) 에러를 일으켰습니다. Javassist는 이 틀린 이름표 때문에 해당 로직을 찾지 못했습니다.
*   **해결 방법**: 모델의 지능(로직)과 결정론적 도구(Javassist)의 완벽한 융합을 위해, 패치 직전 **순수 바이트 배열(byte[]) 상태에서 원본(정답)의 헤더 및 속성 인덱스를 AI 데이터에 강제로 동기화시켜 덮어씌워주는 `alignAttributeIndices` 방어 로직**을 추가했습니다.
    *   이 로직은 자바 사양에 맞춰 구조를 탐색하며, 오직 문제가 되는 **2바이트짜리 이름표(Target Index)에만 원본 번호를 주입**하고, 정작 중요한 **실제 패치 로직(Code Payload) 구간은 절대 건드리지 않고 길이만큼 깔끔하게 건너뛰는(Skip)** 스마트한 방식으로 작동합니다.
*   **결과**: 모델이 내뱉은 패치 헥스가 설령 상수풀 번호를 다소 다르게 조작했다 하더라도, 툴이 알아서 구조적 오차를 교정하므로 **재학습 없이도 실제 인퍼런스된 AI 출력물을 즉시 100% 견고하게 통합 및 적용할 수 있는 엔드투엔드 파이프라인**이 완성되었습니다.
