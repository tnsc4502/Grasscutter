package emu.grasscutter.game.quest;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Transient;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ChapterData;
import emu.grasscutter.data.excels.QuestData;
import emu.grasscutter.data.excels.TriggerExcelConfigData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.quest.enums.QuestTrigger;
import emu.grasscutter.net.proto.ChapterStateOuterClass;
import emu.grasscutter.net.proto.QuestOuterClass.Quest;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.packet.send.PacketChapterStateNotify;
import emu.grasscutter.server.packet.send.PacketQuestListUpdateNotify;
import emu.grasscutter.server.packet.send.PacketQuestProgressUpdateNotify;
import emu.grasscutter.utils.Utils;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
public class GameQuest {
	@Transient @Getter @Setter private GameMainQuest mainQuest;
	@Transient @Getter private QuestData questData;

	@Getter private int subQuestId;
	@Getter private int mainQuestId;
	@Getter @Setter
    private QuestState state;

	@Getter @Setter private int startTime;
	@Getter @Setter private int acceptTime;
	@Getter @Setter private int finishTime;

	@Getter private int[] finishProgressList;
	@Getter private int[] failProgressList;
    @Transient @Getter private Map<String, TriggerExcelConfigData> triggerData;
    @Getter private Map<String, Boolean> triggers;

	@Deprecated // Morphia only. Do not use.
	public GameQuest() {}

	public GameQuest(GameMainQuest mainQuest, QuestData questData) {
		this.mainQuest = mainQuest;
		this.subQuestId = questData.getId();
		this.mainQuestId = questData.getMainId();
		this.questData = questData;
		this.state = QuestState.QUEST_STATE_UNSTARTED;
        this.triggerData = new HashMap<>();
        this.triggers = new HashMap<>();
	}

    public void start() {
        this.acceptTime = Utils.getCurrentSeconds();
        this.startTime = this.acceptTime;
        this.state = QuestState.QUEST_STATE_UNFINISHED;
        List<QuestData.QuestCondition> triggerCond = questData.getFinishCond().stream()
            .filter(p -> p.getType() == QuestTrigger.QUEST_CONTENT_TRIGGER_FIRE).toList();
        if(triggerCond.size() > 0) {
            for (QuestData.QuestCondition cond : triggerCond) {
                TriggerExcelConfigData newTrigger = GameData.getTriggerExcelConfigDataMap().get(cond.getParam()[0]);
                triggerData.put(newTrigger.getTriggerName(),newTrigger);
                triggers.put(newTrigger.getTriggerName(),false);
                SceneGroup group = SceneGroup.of(newTrigger.getGroupId()).load(newTrigger.getSceneId());
                getOwner().getWorld().getSceneById(newTrigger.getSceneId()).loadTriggerFromGroup(group,newTrigger.getTriggerName());
            }
        }

        if (questData.getFinishCond() != null && questData.getFinishCond().size() != 0) {
            this.finishProgressList = new int[questData.getFinishCond().size()];
        }

        if (questData.getFailCond() != null && questData.getFailCond().size() != 0) {
            this.failProgressList = new int[questData.getFailCond().size()];
        }

        getQuestData().getBeginExec().forEach(e -> getOwner().getServer().getQuestHandler().triggerExec(this, e, e.getParam()));


        if (ChapterData.beginQuestChapterMap.containsKey(subQuestId)){
            mainQuest.getOwner().sendPacket(new PacketChapterStateNotify(
                ChapterData.beginQuestChapterMap.get(subQuestId).getId(),
                ChapterStateOuterClass.ChapterState.CHAPTER_STATE_BEGIN
            ));
        }

        //Some subQuests and talks become active when some other subQuests are unfinished (even from different MainQuests)
        this.getOwner().getQuestManager().triggerEvent(QuestTrigger.QUEST_CONTENT_QUEST_STATE_EQUAL, this.getSubQuestId(), this.getState().getValue(),0,0,0);
        this.getOwner().getQuestManager().triggerEvent(QuestTrigger.QUEST_COND_STATE_EQUAL, this.getSubQuestId(), this.getState().getValue(),0,0,0);

        Grasscutter.getLogger().debug("Quest {} is started", subQuestId);
    }
    public String getTriggerNameById(int id) {
        return GameData.getTriggerExcelConfigDataMap().get(id).getTriggerName();
    }
	public Player getOwner() {
		return this.getMainQuest().getOwner();
	}

	public void setConfig(QuestData config) {
        if (config == null) return;
		if (getSubQuestId() != config.getId()) return;
		this.questData = config;
	}

	public void setFinishProgress(int index, int value) {
		finishProgressList[index] = value;
	}

	public void setFailProgress(int index, int value) {
		failProgressList[index] = value;
	}

	public void finish() {
		this.state = QuestState.QUEST_STATE_FINISHED;
		this.finishTime = Utils.getCurrentSeconds();

		if (getFinishProgressList() != null) {
            Arrays.fill(getFinishProgressList(), 1);
		}

		getOwner().getSession().send(new PacketQuestProgressUpdateNotify(this));


		if (getQuestData().finishParent()) {
			// This quest finishes the questline - the main quest will also save the quest to db, so we don't have to call save() here
			getMainQuest().finish();
		}

        getQuestData().getFinishExec().forEach(e -> getOwner().getServer().getQuestHandler().triggerExec(this, e, e.getParam()));
        //Some subQuests have conditions that subQuests are finished (even from different MainQuests)
        getOwner().getQuestManager().triggerEvent(QuestTrigger.QUEST_CONTENT_QUEST_STATE_EQUAL, this.subQuestId, this.state.getValue(),0,0,0);
        getOwner().getQuestManager().triggerEvent(QuestTrigger.QUEST_COND_STATE_EQUAL, this.subQuestId, this.state.getValue(),0,0,0);

        if (ChapterData.endQuestChapterMap.containsKey(subQuestId)){
            mainQuest.getOwner().sendPacket(new PacketChapterStateNotify(
                ChapterData.endQuestChapterMap.get(subQuestId).getId(),
                ChapterStateOuterClass.ChapterState.CHAPTER_STATE_END
            ));
        }

        Grasscutter.getLogger().debug("Quest {} is finished", subQuestId);
	}

    //TODO
    public void fail() {
        this.state = QuestState.QUEST_STATE_FAILED;
        this.finishTime = Utils.getCurrentSeconds();

        if (getFailProgressList() != null) {
            Arrays.fill(getFailProgressList(), 1);
        }

        getOwner().getSession().send(new PacketQuestProgressUpdateNotify(this));


        if (getQuestData().finishParent()) {
            // This quest finishes the questline - the main quest will also save the quest to db, so we don't have to call save() here
            getMainQuest().finish();
        }

        getQuestData().getFailExec().forEach(e -> getOwner().getServer().getQuestHandler().triggerExec(this, e, e.getParam()));
        //Some subQuests have conditions that subQuests fail (even from different MainQuests)
        getOwner().getQuestManager().triggerEvent(QuestTrigger.QUEST_CONTENT_QUEST_STATE_EQUAL, this.subQuestId, this.state.getValue(),0,0,0);
        getOwner().getQuestManager().triggerEvent(QuestTrigger.QUEST_COND_STATE_EQUAL, this.subQuestId, this.state.getValue(),0,0,0);

    }
    //TODO
    public void rewind() {}
	public void save() {
		getMainQuest().save();
	}

	public Quest toProto() {
		Quest.Builder proto = Quest.newBuilder()
				.setQuestId(getSubQuestId())
				.setState(getState().getValue())
				.setParentQuestId(getMainQuestId())
				.setStartTime(getStartTime())
				.setStartGameTime(438)
				.setAcceptTime(getAcceptTime());

		if (getFinishProgressList() != null) {
			for (int i : getFinishProgressList()) {
				proto.addFinishProgressList(i);
			}
		}

		if (getFailProgressList() != null) {
			for (int i : getFailProgressList()) {
				proto.addFailProgressList(i);
			}
		}

		return proto.build();
	}
}
