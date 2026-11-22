package zombie.apocalipse.humans.speech;

import java.util.*;

/** Immersive EN/PTBR field callouts, mirrored by manager migration V44. */
public final class HumanStateSpeechDefaults {
    private HumanStateSpeechDefaults() { }
    private static final String[][] HEALING={
        {"Cover me. I need to tie this off.","Me cobre. Preciso estancar isso."},
        {"Just a bandage. Keep watching the street.","Só um curativo. Fica de olho na rua."},
        {"I've got blood on my sleeve. Give me a moment.","Tem sangue na minha manga. Me dá um instante."},
        {"Hold my flank while I wrap this.","Cobre meu flanco enquanto eu enrolo a faixa."},
        {"Still breathing. Just stopping the bleeding.","Ainda estou respirando. Só vou parar o sangramento."},
        {"I can patch this. Don't let them get close.","Consigo cuidar disso. Não deixa eles chegarem perto."},
        {"One clean dressing, then we move.","Uma faixa limpa e a gente segue."},
        {"Keep your eyes up. Mine are on this wound.","Olhos atentos. Os meus estão neste ferimento."},
        {"This cloth has to hold until we're clear.","Esse pano precisa aguentar até sairmos daqui."},
        {"Almost wrapped. Stay sharp.","Quase terminei a faixa. Fica alerta."}
    };
    private static final String[][] DESPERATION={
        {"Too many! I need a way out!","São muitos! Preciso de uma saída!"},
        {"They're closing in. Cover my retreat!","Estão fechando o cerco. Cobre minha retirada!"},
        {"I can't hold this corner!","Não consigo segurar esta esquina!"},
        {"I'm running out of room here!","Estou ficando sem espaço aqui!"},
        {"Don't leave me surrounded!","Não me deixa cercado!"},
        {"I need breathing room, now!","Preciso de espaço para respirar, agora!"},
        {"They're on every side! Keep a gap open!","Tem bicho de todo lado! Mantém uma passagem aberta!"},
        {"I'm falling back. Stay where I can see you!","Estou recuando. Fica onde eu possa te ver!"},
        {"I won't last here alone!","Não vou aguentar aqui sozinho!"},
        {"This position is lost. Move, move!","Perdemos esta posição. Anda, anda!"}
    };
    private static final String[][] ASSIST={
        {"Hold on! I'm coming to you!","Aguenta! Estou indo até você!"},
        {"I see them on you. Help is coming!","Vi eles em cima de você. A ajuda está chegando!"},
        {"Keep moving toward me. I'll cover you!","Continua vindo na minha direção. Eu te cubro!"},
        {"You're not facing them alone!","Você não vai enfrentar isso sozinho!"},
        {"I've got your flank. Stay with me!","Estou no seu flanco. Fica comigo!"},
        {"Coming through! Give our people some room!","Abrindo caminho! Dá espaço para os nossos!"},
        {"Eyes on your trouble. I'm moving in!","Estou vendo o seu problema. Estou chegando!"},
        {"Fall back this way. I'll meet you!","Recua por aqui. Vou te encontrar!"},
        {"I'm closing the distance. Keep breathing!","Estou chegando mais perto. Continua respirando!"},
        {"Nobody gets left on this street!","Ninguém vai ficar para trás nesta rua!"}
    };
    public static List<HumanSpeechCatalog.Line> lines(){
        var result=new ArrayList<HumanSpeechCatalog.Line>();
        append(result,SpeechEvent.HEALING,"field_dressing",HEALING);
        append(result,SpeechEvent.DESPERATION,"under_siege",DESPERATION);
        append(result,SpeechEvent.ALLY_ASSIST,"stand_together",ASSIST);
        return List.copyOf(result);
    }
    private static void append(List<HumanSpeechCatalog.Line> target,SpeechEvent event,String prefix,String[][] texts){
        for(int i=0;i<texts.length;i++)for(int locale=0;locale<2;locale++)
            target.add(new HumanSpeechCatalog.Line(prefix+"."+String.format(java.util.Locale.ROOT,"%02d",i+1),event,
                locale==0?"EN":"PTBR",texts[i][locale],1,0,1,SpeechContext.AttackKind.ANY,"ANY",0));
    }
}
