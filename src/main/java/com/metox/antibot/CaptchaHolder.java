package com.metox.antibot;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Dogrulama penceresini isaretler ve dogru cevabin yuvasini tutar. */
public class CaptchaHolder implements InventoryHolder {

    private final int answerSlot;
    private Inventory inventory;

    public CaptchaHolder(int answerSlot) {
        this.answerSlot = answerSlot;
    }

    public int getAnswerSlot() {
        return answerSlot;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
