package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Exact existing-call substitutions; no new game fields, overrides or packet formats. */
public final class NpcCombatVisitor extends AsmVisitorWrapper.AbstractBase {
    static final String HOOKS="cloud/apocalipse/pz/agent/features/npc/NpcCombatHooks";
    @Override public int mergeWriter(int flags){return flags|ClassWriter.COMPUTE_MAXS;}
    @Override public ClassVisitor wrap(TypeDescription type,ClassVisitor visitor,Implementation.Context context,TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields,MethodList<?> methods,int writer,int reader){
        return new ClassVisitor(Opcodes.ASM9,visitor){
            int anchors;
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                var delegate=super.visitMethod(access,name,descriptor,signature,exceptions);
                boolean child=type.getName().equals("zombie.network.packets.hit.PlayerHitPlayerPacket");
                boolean parse=child&&name.equals("parse");boolean fall=child&&name.equals("process");
                boolean envelope=type.getName().equals("zombie.network.packets.hit.PlayerHitPacket")&&name.equals("processServer");
                boolean hit=type.getName().equals("zombie.network.fields.hit.Hit")&&name.equals("process")&&descriptor.endsWith("Lzombie/inventory/types/HandWeapon;)V");
                if(!parse&&!fall&&!envelope&&!hit)return delegate;
                return new MethodVisitor(Opcodes.ASM9,delegate){
                    int animalChecks;
                    @Override public void visitMethodInsn(int op,String owner,String method,String desc,boolean itf){
                        if(parse&&owner.equals("zombie/network/fields/hit/Player")&&method.equals("parse")){
                            super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"targetStart","(Ljava/lang/Object;Ljava/lang/Object;)V",false);anchors++;
                        }
                        if(fall&&method.equals("processFall")){
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"processFall","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false);anchors++;return;
                        }
                        if(envelope&&method.equals("sendToRelativeClients")){
                            super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"filterRelay","(Ljava/lang/Object;)V",false);anchors++;
                        }
                        if(hit&&method.equals("process")&&desc.equals("(Lzombie/characters/IsoGameCharacter;Lzombie/characters/IsoGameCharacter;)V")){
                            super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"force","(Ljava/lang/Object;Ljava/lang/Object;)V",false);anchors++;
                        }
                        if(hit&&method.equals("isAnimal")&&desc.equals("()Z")&&++animalChecks==1){
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,"animalOrNpc","(Lzombie/characters/IsoPlayer;)Z",false);anchors++;return;
                        }
                        super.visitMethodInsn(op,owner,method,desc,itf);
                    }
                };
            }
            @Override public void visitEnd(){
                int expected=type.getName().equals("zombie.network.packets.hit.PlayerHitPacket")?1:2;
                if(anchors!=expected)throw new IllegalStateException("NPC combat anchors mismatch: "+type.getName()+" expected="+expected+" actual="+anchors);
                super.visitEnd();
            }
        };
    }
}
