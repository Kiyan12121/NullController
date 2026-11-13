package com.daboss69yt.nullclient;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * The command wheel: one screen with the army orders grouped in rows. Orders that need a target
 * read the name typed in the box at the top, so you can hunt, ambush or strike a roster player
 * without typing the command.
 */
public final class CommandWheelScreen extends Screen {
    private static final int BUTTON_W = 112;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    private static final int COLUMNS = 4;

    private TextFieldWidget targetBox;

    /** One button: its label and the order it sends. A null order means it needs a target name. */
    private record Entry(String label, String order, boolean needsTarget) {
    }

    private static final Entry[][] ROWS = {
            {
                    new Entry("Summon", "summon", false),
                    new Entry("Dismiss", "dismiss", false),
                    new Entry("Recall", "recall", false),
                    new Entry("Follow", "follow", false),
            },
            {
                    new Entry("Guard", "guard", false),
                    new Entry("Stay", "stay", false),
                    new Entry("Status", "status", false),
                    new Entry("Scout", "scout", false),
            },
            {
                    new Entry("Mine", "mine", false),
                    new Entry("Lumber", "lumber", false),
                    new Entry("Farm", "farm", false),
                    new Entry("Patrol", "patrol", false),
            },
            {
                    new Entry("Auto work", "autowork", false),
                    new Entry("Auto spy", "autospy", false),
                    new Entry("Totem", "totem", false),
                    new Entry("Pearl cannon", "pearl", false),
            },
            {
                    new Entry("Prison", "build prison", false),
                    new Entry("Stash room", "stashroom", false),
                    new Entry("Forge", "forge", false),
                    new Entry("Succession", "succession", false),
            },
            {
                    new Entry("Hunt", "hunt", true),
                    new Entry("Ambush", "ambush", true),
                    new Entry("Orbital", "orbital", true),
                    new Entry("Stab shot", "stabshot", true),
            },
    };

    public CommandWheelScreen() {
        super(Text.literal("Null Command Wheel"));
    }

    @Override
    protected void init() {
        int rows = ROWS.length;
        int gridW = COLUMNS * BUTTON_W + (COLUMNS - 1) * GAP;
        int left = this.width / 2 - gridW / 2;
        int top = this.height / 2 - (rows * (BUTTON_H + GAP) + 40) / 2;

        targetBox = new TextFieldWidget(this.textRenderer, left, top + 20, gridW, BUTTON_H,
                Text.literal("Target name"));
        targetBox.setPlaceholder(Text.literal("Target name for Hunt / Ambush / Orbital / Stab shot"));
        this.addDrawableChild(targetBox);

        int y = top + 48;
        for (Entry[] row : ROWS) {
            for (int c = 0; c < row.length; c++) {
                Entry e = row[c];
                int x = left + c * (BUTTON_W + GAP);
                this.addDrawableChild(ButtonWidget.builder(Text.literal(e.label()), b -> press(e))
                        .dimensions(x, y, BUTTON_W, BUTTON_H)
                        .build());
            }
            y += BUTTON_H + GAP;
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
                .dimensions(this.width / 2 - 40, y + 8, 80, BUTTON_H)
                .build());
    }

    /** Sends the entry's order. Target orders use the name in the box and refuse if it is empty. */
    private void press(Entry e) {
        if (!e.needsTarget()) {
            NullClientMod.send(e.order());
            return;
        }
        String name = targetBox.getText().trim();
        if (name.isEmpty()) {
            targetBox.setFocused(true);
            return;
        }
        NullClientMod.send(e.order() + " " + name);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
