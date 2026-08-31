package com.ashvehicles.data;

/**
 * {@code ashvehiclespack/} を初めて作ったときに置いていく1枚。
 *
 * <p>フォルダだけ作って何も言わないと、そこは「空のフォルダ」であって「置き場」には見えない。ここに
 * 書いてあるのは、置く物の形と、名前空間がフォルダ名だということと、置いた物がどこで効くか。
 */
final class ContentPackTemplate {
    static final String README = """
            AshVehicles content packs
            =========================

            Drop a .zip in this folder. That is the whole install step: there is no list to enable it
            in, no world to add it to, and no resource pack screen to reorder. What is here at launch
            is loaded; what is added while the game runs is picked up at the next launch.

            An unzipped folder works too. Use that while you are building one and zip it to hand out.


            What goes inside
            ----------------

            The layout is the same one Minecraft itself uses, and the same one this mod uses:

                mypack.zip
                  data/mypack/aircraft/foo.json          <- one file, one aeroplane
                  data/mypack/vehicle/bar.json           <- one file, one ground vehicle or ship
                  data/mypack/weapon/baz.json            <- guns, bombs, missiles, rockets
                  data/mypack/rack/rail.json             <- pylons and ejector racks
                  data/mypack/equipment/pod.json         <- pods
                  assets/mypack/geo/entity/foo.geo.json  <- the model
                  assets/mypack/textures/entity/foo.png  <- its skin
                  assets/mypack/animations/entity/foo.animation.json
                  assets/mypack/lang/en_us.json          <- the names people read

            No pack.mcmeta is needed. There is nothing to declare: the folder is only read by this
            mod, and this mod already knows what it is looking at.


            Names
            -----

            The folder under data/ and assets/ is the namespace, and the file name is the rest of it.
            data/mypack/aircraft/foo.json is the aeroplane "mypack:foo". Its entity and its item are
            registered under that name, its model is looked for at assets/mypack/geo/entity/foo.geo.json,
            and its skin at assets/mypack/textures/entity/foo.png -- the same word in both places.

            Because the namespace is yours, two packs can both ship a "foo" without either one losing.
            Naming your namespace "ashvehicles" is how you would replace something the mod already
            ships; do it on purpose or not at all.

            Give it a display name in assets/mypack/lang/en_us.json:

                {
                  "entity.mypack.foo": "Example Fighter",
                  "item.mypack.foo": "Example Fighter"
                }


            The rest of the files
            ---------------------

            Aircraft and ground vehicles get their inventory icon for free -- the mod draws it from
            the model you already shipped, and writes the item model itself. Everything else needs
            the ordinary files, under your own namespace:

                assets/mypack/models/item/mymissile.json      <- a plain "item/generated" model
                assets/mypack/textures/item/mymissile.png
                assets/mypack/sounds.json                     <- engine and gun sounds
                assets/mypack/sounds/engine/foo.ogg

            Sound names follow the thing that makes them, in its own namespace: the aeroplane
            "mypack:foo" looks for the sound event "mypack:engine.foo", and the gun "mypack:mygun"
            for "mypack:weapon.mygun". Declare them in your own sounds.json.

            Anything else a data pack can hold works here too, because that is what this folder hands
            to the game. Recipes are the useful one: put

                data/mypack/recipe/foo.json

            with type "ashvehicles:vehicle_crafting" and your aeroplane can be built at the vehicle
            workbench like the rest. Copy one of the mod's own recipe files to see the shape.

            The workbench keeps its list on four tabs, and an optional "tab" field says which one a
            recipe lands on: "vehicle" (the default, so aircraft and vehicles need not say it),
            "weapon" for missiles and bombs, "equipment" for racks and pods, "ammo" for what feeds
            a gun.


            What to copy from
            -----------------

            Every aircraft, vehicle and weapon this mod ships is a file of exactly this kind. Open the
            mod's jar with any zip program and look under data/ashvehicles/ and assets/ashvehicles/ --
            those files are the reference, and copying the closest one and editing it is the intended
            way to start.


            When something does not appear
            ------------------------------

            Read the log. Every file that cannot be parsed is named there along with what was wrong
            with it, and so is every name that lost a fight with a name already taken.
            """;

    private ContentPackTemplate() {
    }
}
