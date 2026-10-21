package me.mlgbot;

/** Configuracoes do bot de cada jogador (as do livro/menu). */
public class PlayerPrefs {

    /** Modos de treino do menu. */
    public enum Training {
        AIM_REDUCE, FASTCLUTCH, CLASSIC, BRIDGING
    }

    public Training training = Training.AIM_REDUCE;

    /** Forca horizontal do golpe do bot. */
    public double hitH;
    /** Forca vertical do golpe do bot. */
    public double hitV;
    /** Alcance do ataque do bot (blocos). */
    public double radius = 3.5;
    /** Classic clutcher: golpes seguidos em cada serie. */
    public int seriesHits = 3;
    /** Classic/Fastclutch: pausa entre as series (segundos). */
    public double seriesPause = 2.0;
    /** Bot fica na altura do jogador. */
    public boolean chillOut;
    /** Bot bate no mesmo tempo que o jogador. */
    public boolean epicMoves;

    /** Nome e skin do bot (null = padrao). */
    public String botNick;
    public String skinNick;
    public String skinValue;
    public String skinSignature;

    /** Ponto de chegada do modo Bridging. */
    public boolean finishSet;
    public String finishWorld;
    public double finishX, finishY, finishZ;
    /** Recorde de golpes aguentados sem cair (modos com bot). */
    public int bestHits;

    /** Melhor tempo no Bridging (segundos), 0 = nenhum. */
    public double bestTime;

    public PlayerPrefs(double hitH, double hitV) {
        this.hitH = hitH;
        this.hitV = hitV;
    }
}
