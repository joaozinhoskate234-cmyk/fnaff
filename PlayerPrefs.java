package me.mlgbot;

/** Configuracoes do bot de cada jogador (as do livro/menu). */
public class PlayerPrefs {

    /** Forca horizontal do golpe do bot. */
    public double hitH;
    /** Forca vertical do golpe do bot. */
    public double hitV;
    /** Bot fica na altura do jogador. */
    public boolean chillOut;
    /** Bot bate no mesmo tempo que o jogador. */
    public boolean epicMoves;

    public PlayerPrefs(double hitH, double hitV) {
        this.hitH = hitH;
        this.hitV = hitV;
    }
}
