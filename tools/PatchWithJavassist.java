import javassist.*;
import javassist.bytecode.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 PatchWithJavassist 도구
 
 목적: 패치된 메소드의 16진수(Hex) 바이트코드를 원본 클래스 파일에 주입하는 도구입니다.
 핵심 기능:
 1. 바이트코드 파싱: Hex 문자열을 Java의 바이트 배열로 변환
 2. CP Remapping: Reference Class(패치된 클래스)의 Constant Pool을 이용해 패치 데이터의 인덱스를 Target Class(원본 버그 클래스)에 맞게 자동 변환
 3. ASM 프레임 재계산: 변조된 바이트코드의 StackMapTable을 ASM을 통해 새로 계산하여 VerifyError 방지
 */
public class PatchWithJavassist {
    public static void main(String[] args) {
        // 인자 파싱: 최소 3개
        if (args.length < 3) {
            System.err.println("Usage: java -cp ... PatchWithJavassist <OriginalClassFile> <PatchedHexFile> <OutputClassFile> [--ref ReferenceClassFile | --diff OriginalHexFile]");
            System.exit(1);
        }

        String classFilePath = args[0];       // 원본 클래스 파일 경로 (Target)
        String hexFilePath = args[1];         // 패치 데이터(.txt) 파일 경로
        String outputFilePath = args[2];      // 결과물이 저장될 경로
        
        String referenceClassPath = null;
        String originalHexPath = null;
        
        if (args.length >= 5) {
            if ("--ref".equals(args[3])) {
                referenceClassPath = args[4];
            } else if ("--diff".equals(args[3])) {
                originalHexPath = args[4];
            }
        } else if (args.length == 4) {
            // 하위 호환성 (기존 스크립트가 4번째 인자로 Ref Class를 전달)
            if (!args[3].startsWith("--")) {
                referenceClassPath = args[3];
            }
        }

        try {
            // 1. 패치 Hex 파싱
            String patchHexString = new String(Files.readAllBytes(Paths.get(hexFilePath))).trim().replaceAll("\\s+", "");
            byte[] patchedMethodBytes = hexStringToByteArray(patchHexString);

            // 2. 원본 클래스 로드
            ClassPool pool = ClassPool.getDefault();
            File classFile = new File(classFilePath).getAbsoluteFile();
            pool.insertClassPath(classFile.getParent());
            
            CtClass targetCc = null;
            try (InputStream in = new FileInputStream(classFile)) {
                 targetCc = pool.makeClass(in);
            }

            ConstPool targetCp = targetCc.getClassFile().getConstPool();

            if (originalHexPath != null) {
                // =============== [MODE C] DIFFERENTIAL PATCHING ===============
                System.out.println("Using Differential Patching Mode (--diff)");
                System.out.println("Original Hex File: " + originalHexPath);
                
                // 원본 Hex 파일 읽기
                if (!new File(originalHexPath).exists()) {
                    System.err.println("Original Hex file not found: " + originalHexPath);
                }
                String origHexString = new String(Files.readAllBytes(Paths.get(originalHexPath))).trim().replaceAll("\\s+", "");
                byte[] origMethodBytes = hexStringToByteArray(origHexString);
                
                // =============== FIX T5 HALLUCINATIONS ===============
                System.out.println("Synchronizing attribute indices from Original Hex to Patched Hex...");
                alignAttributeIndices(patchedMethodBytes, origMethodBytes, targetCp);
                
                // 원본 Hex를 타겟 CP로 파싱하여 정확한 메소드 이름/타입 알아내기
                MethodInfo originalMethodInfo = createMethodInfoFromBytes(origMethodBytes, targetCp, true);
                String methodName = originalMethodInfo.getName();
                String methodDesc = originalMethodInfo.getDescriptor();

                // 패치 데이터를 타겟 CP 기준으로 파싱 (이름 불일치 무시)
                MethodInfo patchedMethodInfo = createMethodInfoFromBytes(patchedMethodBytes, targetCp, false);

                
                // 타겟 클래스에서 원본 메소드 찾기
                boolean isConstructor = methodName.equals("<init>");
                boolean isClassInitializer = methodName.equals("<clinit>");
                CtBehavior targetMethod = null;
                
                if (isConstructor) {
                    for (CtConstructor c : targetCc.getConstructors()) {
                        if (c.getMethodInfo().getDescriptor().equals(methodDesc)) {
                            targetMethod = c;
                            break;
                        }
                    }
                } else if (isClassInitializer) {
                    targetMethod = targetCc.getClassInitializer();
                } else {
                    targetMethod = targetCc.getMethod(methodName, methodDesc);
                }
                
                if (targetMethod == null) {
                    throw new RuntimeException("Original method not found in target class!");
                }
                
                // 핵심 차분 패치: CodeAttribute만 가져와서 갈아끼움
                CodeAttribute patchedCodeAttr = patchedMethodInfo.getCodeAttribute();
                if (patchedCodeAttr != null) {
                    // 기존 메소드의 Code Attribute를 새 것으로 교체 (이름/접근제어자/상수풀 모두 원본 100% 보존)
                    targetMethod.getMethodInfo().removeAttribute(patchedCodeAttr.getName());
                    targetMethod.getMethodInfo().addAttribute(patchedCodeAttr);
                    System.out.println("Successfully replaced CodeAttribute via differential patching.");
                } else {
                    throw new RuntimeException("Patch rejected: Malformed CodeAttribute in patched method. Skipping class generation.");
                }
                
            } else if (referenceClassPath != null) {
                // =============== [MODE A] CP REMAPPING ===============
                System.out.println("Using Reference Class for Constant Pool: " + referenceClassPath);
                File refFile = new File(referenceClassPath).getAbsoluteFile();
                
                ClassPool refPool = new ClassPool(true); 
                refPool.insertClassPath(refFile.getParent());
                CtClass refCc = null;
                try (InputStream in = new FileInputStream(refFile)) {
                    refCc = refPool.makeClass(in);
                }
                ConstPool refCp = refCc.getClassFile().getConstPool();
                
                MethodInfo patchedMethodInfo = createMethodInfoFromBytes(patchedMethodBytes, refCp, false);
                
                removeMethodFromTarget(targetCc, patchedMethodInfo.getName(), patchedMethodInfo.getDescriptor());
                
                CtMethod srcMethod = CtMethod.make(patchedMethodInfo, refCc);
                CtMethod newMethod = CtNewMethod.copy(srcMethod, targetCc, null);
                targetCc.addMethod(newMethod);
                
            } else {
                // =============== [MODE B] DIRECT INJECTION ===============
                System.out.println("No Reference or Diff provided. Using Target Class Constant Pool for direct injection.");
                MethodInfo patchedMethodInfo = createMethodInfoFromBytes(patchedMethodBytes, targetCp, false);
                
                removeMethodFromTarget(targetCc, patchedMethodInfo.getName(), patchedMethodInfo.getDescriptor());
                targetCc.getClassFile().addMethod(patchedMethodInfo);
            }

            // 프레임 재계산 및 저장 (모든 모드 공통)
            byte[] intermediateBytes = targetCc.toBytecode();
            System.out.println("Recomputing frames with ASM...");
            ClassReader cr = new ClassReader(intermediateBytes);
            // CRITICAL FIX: To preserve CP relative indices during multiple patch operations,
            // we must pass the ClassReader to the ClassWriter constructor.
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES); 
            cr.accept(cw, 0);
            
            byte[] finalBytes = cw.toByteArray();

            System.out.println("Writing final patched class to: " + outputFilePath);
            try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
                fos.write(finalBytes);
            }
            
            File outFile = new File(outputFilePath);
            if (outFile.length() > 0) {
                System.out.println("[SUCCESS] Class file generated: " + outFile.getAbsolutePath());
                System.exit(0);
            } else {
                throw new RuntimeException("Generated class file is empty.");
            }

        } catch (Exception e) {
            System.err.println("[FAILURE] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void removeMethodFromTarget(CtClass targetCc, String methodName, String methodDesc) throws Exception {
        if (methodName.equals("<init>")) {
            for (CtConstructor c : targetCc.getConstructors()) {
                if (c.getMethodInfo().getDescriptor().equals(methodDesc)) {
                    targetCc.removeConstructor(c);
                    System.out.println("Removed existing constructor.");
                    break;
                }
            }
        } else if (methodName.equals("<clinit>")) {
            CtConstructor classInit = targetCc.getClassInitializer();
            if (classInit != null) {
                targetCc.removeConstructor(classInit);
                System.out.println("Removed existing class initializer.");
            }
        } else {
            try {
                CtMethod existingMethod = targetCc.getMethod(methodName, methodDesc);
                targetCc.removeMethod(existingMethod);
                System.out.println("Removed existing method.");
            } catch (NotFoundException e) {
                System.out.println("Method not found in target (new method?). Proceeding.");
            }
        }
    }

    private static MethodInfo createMethodInfoFromBytes(byte[] methodBytes, ConstPool cp, boolean isOriginal) throws Exception {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(methodBytes))) {
            int accessFlags = dis.readUnsignedShort();
            int nameIndex = dis.readUnsignedShort();
            int descriptorIndex = dis.readUnsignedShort();
            
            String methodName = "UnknownName";
            String methodDesc = "UnknownDesc";
            try {
                methodName = cp.getUtf8Info(nameIndex);
                methodDesc = cp.getUtf8Info(descriptorIndex);
            } catch (Exception e) {
                if (isOriginal) {
                    throw new RuntimeException("Original hex name/desc could not be resolved in Target CP.", e);
                } else {
                    System.out.println("Warning: Could not resolve patched method name/desc in Target CP. Using dummy names.");
                }
            }
            
            System.out.println("Parsed Header: " + methodName + " " + methodDesc);
            
            MethodInfo methodInfo = new MethodInfo(cp, methodName, methodDesc);
            methodInfo.setAccessFlags(accessFlags);
            
            int attrCount = dis.readUnsignedShort();
            System.out.println("Attribute Count: " + attrCount);
            
            java.lang.reflect.Method readMethod = AttributeInfo.class.getDeclaredMethod("read", ConstPool.class, DataInputStream.class);
            readMethod.setAccessible(true);

            for (int i = 0; i < attrCount; i++) {
                try {
                    AttributeInfo attr = (AttributeInfo) readMethod.invoke(null, cp, dis);
                    if (attr != null) methodInfo.addAttribute(attr);
                } catch (Exception e) {
                    System.out.println("Warning: Failed to parse an attribute. Skipping.");
                }
            }
            return methodInfo;
        }
    }

    /**
     * T5 모델의 상수풀 인덱스 예측 환각(Hallucination) 현상을 방어하기 위한 동기화 함수.
     * 원본 바이트코드(정답)의 헤더 구조를 따라가면서, 패치된 바이트코드(AI 모델 출력)에 적힌 
     * 미세하게 빗나간 상수풀 인덱스 값들을 원본의 정확한 인덱스 값으로 강제 교정(덮어쓰기)합니다.
     */
    private static void alignAttributeIndices(byte[] patched, byte[] orig, ConstPool cp) {
        try {
            java.nio.ByteBuffer pBuf = java.nio.ByteBuffer.wrap(patched);
            java.nio.ByteBuffer oBuf = java.nio.ByteBuffer.wrap(orig);
            
            // 1. 헤더: access_flags (2 bytes) 스킵
            pBuf.getShort(); oBuf.getShort(); 
            
            // 2. 헤더: name_index (2 bytes) 동기화 (메소드 이름)
            int oName = oBuf.getShort() & 0xFFFF; // 원본에서 정확한 인덱스 읽기
            int pNamePos = pBuf.position();       // 패치 배열 내의 덮어쓸 위치 기억
            pBuf.getShort();                      // 패치의 (아마도 틀렸을) 인덱스 스킵
            patched[pNamePos] = (byte)(oName >> 8); // 원본 인덱스로 덮어쓰기 (상위 1바이트)
            patched[pNamePos+1] = (byte)(oName);    // 원본 인덱스로 덮어쓰기 (하위 1바이트)
            
            // 3. 헤더: descriptor_index (2 bytes) 동기화 (파라미터/반환타입)
            int oDesc = oBuf.getShort() & 0xFFFF;
            int pDescPos = pBuf.position();
            pBuf.getShort();
            patched[pDescPos] = (byte)(oDesc >> 8);
            patched[pDescPos+1] = (byte)(oDesc);
            
            // 4. 헤더: 속성 개수 (2 bytes) 파악
            int pAttrCount = pBuf.getShort() & 0xFFFF;
            int oAttrCount = oBuf.getShort() & 0xFFFF;
            
            // 양쪽 배열에 공통으로 존재하는 속성(Attribute) 개수만큼 내부 봉투 탐색 시작
            int minAttr = Math.min(pAttrCount, oAttrCount);
            for (int i = 0; i < minAttr; i++) {
                alignAttribute(pBuf, oBuf, patched, cp);
            }
        } catch (Exception e) {
            System.out.println("Warning: Attribute alignment incomplete: " + e.getMessage());
        }
    }

    /**
     * 개별 속성(Attribute) 내부를 탐색하며 이름표(인덱스)를 교정하고, 
     * 실제 패치 로직(Payload) 구간은 안전하게 건너뛰는(Skip) 함수입니다.
     */
    private static void alignAttribute(java.nio.ByteBuffer pBuf, java.nio.ByteBuffer oBuf, byte[] patched, ConstPool cp) {
        // [중요 교정 포인트]: 이 속성 봉투의 "이름표" 번호 동기화 (예: "Code"라는 글자를 가리키는 번호)
        int oNameIdx = oBuf.getShort() & 0xFFFF;
        int pNameIdxPos = pBuf.position();
        pBuf.getShort();
        
        patched[pNameIdxPos] = (byte)(oNameIdx >> 8);
        patched[pNameIdxPos+1] = (byte)(oNameIdx);
        
        // 이 속성 봉투의 내용물 총 길이 파악 (4 bytes)
        int pLen = pBuf.getInt();
        int oLen = oBuf.getInt();
        
        // 이 봉투가 끝나는 지점의 좌표 기억
        int pEnd = pBuf.position() + pLen;
        int oEnd = oBuf.position() + oLen;
        
        // 방금 동기화한 원본 이름표 번호를 통해, 이 봉투가 무슨 봉투인지 실제 글자 확인
        String attrName = null;
        try { attrName = cp.getUtf8Info(oNameIdx); } catch(Exception e) {}
        
        // 만약 이 봉투가 우리가 수정해야 할 목표인 "Code" 속성이라면 상세 해부 시작!
        if ("Code".equals(attrName)) {
            pBuf.getShort(); oBuf.getShort(); // max_stack 스킵
            pBuf.getShort(); oBuf.getShort(); // max_locals 스킵
            
            // 🚨 핵심 로직(Payload) 길이 파악 (4 bytes) 🚨
            int pCodeLen = pBuf.getInt();
            int oCodeLen = oBuf.getInt();
            
            // AI 모델이 고안해낸 진짜 패치된 바이트코드 영역! 
            // 여기는 모델의 지능이 담긴 결과물이므로 절대 건드리지 않고, 길이만큼 훌쩍 건너뜀 (Skip)
            pBuf.position(pBuf.position() + pCodeLen);
            oBuf.position(oBuf.position() + oCodeLen);
            
            // 예외 처리 테이블(Exception Table) 길이 파악 후 건너뜀
            int pExcLen = pBuf.getShort() & 0xFFFF;
            int oExcLen = oBuf.getShort() & 0xFFFF;
            
            pBuf.position(pBuf.position() + pExcLen * 8); // 1개의 예외 처리는 8바이트 구성
            oBuf.position(oBuf.position() + oExcLen * 8);
            
            // Code 속성 안에 또 다른 아기 속성들(LineNumberTable 등)이 있으므로 재귀 탐색
            int pInnerCount = pBuf.getShort() & 0xFFFF;
            int oInnerCount = oBuf.getShort() & 0xFFFF;
            
            int minInner = Math.min(pInnerCount, oInnerCount);
            for (int i = 0; i < minInner; i++) {
                alignAttribute(pBuf, oBuf, patched, cp);
            }
        } else {
            // 우리가 관심 없는 쓰레기 봉투(LineNumberTable 등)라면 교정할 필요 없이 바로 끝지점으로 점프
            pBuf.position(pEnd);
            oBuf.position(oEnd);
        }
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int digit1 = Character.digit(s.charAt(i), 16);
            int digit2 = Character.digit(s.charAt(i+1), 16);
            if (digit1 == -1 || digit2 == -1) continue;
            data[i / 2] = (byte) ((digit1 << 4) + digit2);
        }
        return data;
    }
}
