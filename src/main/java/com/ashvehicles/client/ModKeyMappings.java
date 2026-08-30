package com.ashvehicles.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 機体の操作。3つの回転軸を直接操縦する。W/S がピッチ、A/D がロール、Q/E がヨー、シフトとコントロールがスロットル。
 *
 * <p>いくつかはバニラが既に使っているキーに置いてある。意図的だ——シフトとコントロールはスロットルとして指が届く
 * 位置にある——し、飛行中にバニラがそれらでやることは {@link AircraftInputHandler} が飲み込む。特にシフトはもう
 * 「降りる」を意味しない。ここでは全機体の全座席で alt がそれだ——{@link VehicleDismountHandler} 参照。
 */
public final class ModKeyMappings {
    private static final String CATEGORY = "key.categories.ashvehicles";

    /**
     * 操縦桿。
     *
     * <p>この4つは以前、自前のバインドを持たずバニラの移動キーから直接読んでいた。飛ぶには何の問題も無かったが
     * 変更できなかった。ピッチとロールが操作設定画面にまったく現れないので、機首を別のキー組に置きたいパイロットに
     * はそう言う場所が無かったし、<em>歩行</em>を WASD から移した人は、他に何もしなくなったキーに機体がまだ応える
     * ことに気付いた。
     *
     * <p>既定値は移動キーのままなので、それで満足していた人には何も変わらない。操作設定画面では歩行と衝突している
     * と表示される——それは事実であり、意図的であり、ここのスロットルやフラップが既にやっていることと同じだ。機体に
     * 固定されている間に歩く者はいない。
     *
     * <p>フライトシミュレータと同様、操縦桿を前へ倒すと機首が下がるので、前進キーは {@code pitch_up} ではなく
     * {@code pitch_down} だ。
     */
    public static final KeyMapping PITCH_DOWN = create("pitch_down", GLFW.GLFW_KEY_W);
    public static final KeyMapping PITCH_UP = create("pitch_up", GLFW.GLFW_KEY_S);
    public static final KeyMapping ROLL_LEFT = create("roll_left", GLFW.GLFW_KEY_A);
    public static final KeyMapping ROLL_RIGHT = create("roll_right", GLFW.GLFW_KEY_D);

    public static final KeyMapping THROTTLE_UP = create("throttle_up", GLFW.GLFW_KEY_LEFT_SHIFT);
    public static final KeyMapping THROTTLE_DOWN = create("throttle_down", GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping YAW_LEFT = create("yaw_left", GLFW.GLFW_KEY_Q);
    public static final KeyMapping YAW_RIGHT = create("yaw_right", GLFW.GLFW_KEY_E);
    public static final KeyMapping AIR_BRAKE = create("air_brake", GLFW.GLFW_KEY_B);
    public static final KeyMapping TOGGLE_GEAR = create("toggle_gear", GLFW.GLFW_KEY_G);
    public static final KeyMapping TOGGLE_FLAPS = create("toggle_flaps", GLFW.GLFW_KEY_F);
    /** 揚力系を持つ機体のノズルを下げ、また上げる。それ以外の機体では何もしない。 */
    public static final KeyMapping TOGGLE_VTOL = create("toggle_vtol", GLFW.GLFW_KEY_R);
    /** パイロン上の物を順送りする。トリガー自体はバニラの攻撃ボタン。 */
    public static final KeyMapping CYCLE_WEAPON = create("cycle_weapon", GLFW.GLFW_KEY_X);
    /**
     * 吊っている増槽を全部切り離す。飛行中でも構わないし、むしろそのためにある。
     *
     * <p>落とした物は返らない——投棄であって取り外しではない。持ち帰りたければ地上でレンチを使う。空になった
     * 増槽も抗力を払い続けるので、いつ落とすかはパイロットの判断として残る。
     */
    public static final KeyMapping JETTISON = create("jettison", GLFW.GLFW_KEY_J);
    /**
     * 2つの対抗手段のハンドル。1つではなく別々のキーにしてある。どちらが正解かは警戒受信機が今答えた問いだからだ。
     * 赤外線誘導にはフレア、レーダー反射誘導にはチャフ。押し続ければ放出し続ける。
     */
    public static final KeyMapping RELEASE_FLARE = create("release_flare", GLFW.GLFW_KEY_C);
    public static final KeyMapping RELEASE_CHAFF = create("release_chaff", GLFW.GLFW_KEY_V);

    /**
     * 押している間、シーカーに目標を捕捉させる。既に捉えている物を保持するだけではない。
     *
     * <p>これが無いと、レーダー誘導兵器は選択した瞬間に円錐内の何かへロックしてしまい、パイロットには手の出しよう
     * が無い——レーダーの働き方でもなければ、人に委ねる判断としても物足りない。連打ではなく押し続けにしてあるのは、
     * 閉じる前に離したロックは最初から取っていないロックだからだ。閉じた後はシーカーが自力で保持するのでキーは離せる。
     * {@link com.ashvehicles.weapon.TargetLock} 参照。
     *
     * <p>コックピット専用ではない。対空車両の砲手も同じキーで同じことをする。発射筒を持つ車両のシーカーは、以前は
     * 砲塔が向いた先の物を無条件に掴んでいたが、乗る物によってロックの取り方が変わる理由は無かった。
     * {@link com.ashvehicles.weapon.TurretLauncher} 参照。
     */
    public static final KeyMapping RADAR_LOCK = create("radar_lock", GLFW.GLFW_KEY_L);

    /**
     * 押している間、マウスによる操縦をやめて見回しに使う。
     *
     * <p>手が既に置かれているマウス中ボタンに割り当てる。ポインティング飛行は、マウス本来の用途——肩越しに振り返る
     * こと——をパイロットから奪うが、戦闘の大半は後ろに何がいるかを知りたい時間だ。離せば視界はマークへ戻る。
     *
     * <p>バニラのブロック選択に重なるが、あれはコックピット内では無用であり、飛行中は
     * {@link AircraftInputHandler} が飲み込む。
     */
    public static final KeyMapping FREE_LOOK = create("free_look", InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
    /** 機体の操縦を完全にキーへ、マウスを見回しへ戻す。 */
    public static final KeyMapping TOGGLE_MOUSE_AIM = create("toggle_mouse_aim", GLFW.GLFW_KEY_M);
    /**
     * 押している間、照準を上げる。砲が据えられている物へ視界が狭まり、マウスもそれに合わせて遅くなる。あらゆる物の
     * 操縦席で共通の1キー。{@link AimZoom} 参照。
     *
     * <p>他のどのゲームもそうしているマウス右ボタンに割り当てる。これはバニラの使用キーだが、コックピットや砲塔の
     * 内側では使う物が無く、その下の攻撃ボタンと同様に {@link AircraftInputHandler} と
     * {@link GroundVehicleInputHandler} が操縦席で飲み込む。
     */
    public static final KeyMapping AIM = create("aim", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

    /**
     * ターゲティングポッドのマークを十字線の下の物へ据え、また解放する。
     *
     * <p>意味を持つのはポッド視界が上がっている間だけ。{@link PodCamera} 参照。ジャンプキーに割り当てるが、
     * コックピット内では跳ぶ足場が無く、攻撃・使用ボタンと同様に {@link AircraftInputHandler} が操縦席で飲み込む。
     */
    public static final KeyMapping DESIGNATE = create("designate", GLFW.GLFW_KEY_SPACE);

    /**
     * 運転手の操作。パイロットとは別の組で、バインドも別々。
     *
     * <p>既定では同じ4キーに落ちる。物を動かすとき誰もが手を伸ばすのがその4キーだし、プレイヤーが飛行と運転を同時
     * にすることは無いからだ。バインドを共有する方が、重複より悪い。操縦桿と操向レバーは別の物だ——操縦桿を前へ倒す
     * と機首が下がり、レバーを前へ倒すと前進する——し、両者を別のキーに置きたい人にはそう言う場所が無くなる。
     */
    public static final KeyMapping DRIVE_FORWARD = create("drive_forward", GLFW.GLFW_KEY_W);
    public static final KeyMapping DRIVE_BACK = create("drive_back", GLFW.GLFW_KEY_S);
    public static final KeyMapping STEER_LEFT = create("steer_left", GLFW.GLFW_KEY_A);
    public static final KeyMapping STEER_RIGHT = create("steer_right", GLFW.GLFW_KEY_D);
    /** 車両をその場に止め、坂でも保持する。 */
    public static final KeyMapping VEHICLE_BRAKE = create("vehicle_brake", GLFW.GLFW_KEY_SPACE);
    /**
     * 同軸機銃のトリガー（搭載車両のみ）。
     *
     * <p>兵装サイクルの一員ではなく専用キーにしてある。同軸機銃は乗員が選ぶ選択肢の1つではないからだ。主砲に固定
     * され、主砲が据えられた所へ据えられ、その用途の全ては「砲手が既に捉えている物へ、主砲を仕舞わずに掃射する」
     * ことだ。攻撃ボタンは従来通り——選択中の兵装——で、これは同じ防盾のもう1本の銃身である。
     *
     * <p>{@code Z} はバニラのゲーム内キーと衝突せず、運転キーに置かれた手——砲塔を据えるマウスに置かれていない方の
     * 手——の下に来る。
     */
    public static final KeyMapping FIRE_COAXIAL = create("fire_coaxial", GLFW.GLFW_KEY_Z);

    /**
     * MOD 内のあらゆる物から降りる手段。コックピットでも運転席でも、前席でも後席でも。
     *
     * <p>シフトではない。あれはバニラの降車キーだが、ここではスロットルだ。出力を入れて上昇中のパイロットは、その
     * 操作で機外へ出てしまう。機体ごとに1キーではなく全座席で1キーにしてある。降りる前にどの座席にいるか思い出さ
     * ねばならない乗員には、間違ったキーが割り当てられているからだ。読むのは {@link VehicleDismountHandler}。
     */
    public static final KeyMapping DISMOUNT = create("dismount", GLFW.GLFW_KEY_LEFT_ALT);

    /**
     * 乗員が搭乗している機体の弾庫を開く。機外からはスニーク＋機体本体の右クリックで開く。
     *
     * <p>手が伸びるであろうインベントリキーには置かない。あれはプレイヤー自身のインベントリを開くし、各プレイヤーが
     * 好きに割り当てているし、ここが見る前にゲームが読んでしまう。パイロットからそれを奪うのは、全プレイヤーが開ける
     * と期待している唯一の画面を奪うことだ。{@code I} は隣のキーで空いている。
     */
    public static final KeyMapping OPEN_HOLD = create("open_hold", GLFW.GLFW_KEY_I);

    /**
     * 乗員を、搭乗中の機体の次の座席へ移す——運転席も含むので、1人でも全座席を順に渡り歩ける。MOD 内すべての全座席
     * で共通の1キーで、搭乗中のみ有効。次がどの座席かはサーバーが決める。
     * {@link com.ashvehicles.network.SwitchSeatPayload} 参照。{@code K} はバニラのゲーム内キーと衝突せず、既に移動
     * キーに置かれている手の下に来る。
     */
    public static final KeyMapping SWITCH_SEAT = create("switch_seat", GLFW.GLFW_KEY_K);

    public static final KeyMapping[] ALL = {PITCH_UP, PITCH_DOWN, ROLL_LEFT, ROLL_RIGHT,
            THROTTLE_UP, THROTTLE_DOWN, YAW_LEFT, YAW_RIGHT,
            AIR_BRAKE, TOGGLE_GEAR, TOGGLE_FLAPS, TOGGLE_VTOL, CYCLE_WEAPON, JETTISON,
            RELEASE_FLARE, RELEASE_CHAFF,
            RADAR_LOCK, FREE_LOOK, TOGGLE_MOUSE_AIM, AIM, DESIGNATE,
            DRIVE_FORWARD, DRIVE_BACK, STEER_LEFT, STEER_RIGHT, VEHICLE_BRAKE, FIRE_COAXIAL,
            DISMOUNT, OPEN_HOLD, SWITCH_SEAT};

    private static KeyMapping create(String name, int key) {
        return create(name, InputConstants.Type.KEYSYM, key);
    }

    private static KeyMapping create(String name, InputConstants.Type type, int key) {
        return new KeyMapping("key.ashvehicles." + name, KeyConflictContext.IN_GAME, type, key, CATEGORY);
    }

    private ModKeyMappings() {
    }
}
