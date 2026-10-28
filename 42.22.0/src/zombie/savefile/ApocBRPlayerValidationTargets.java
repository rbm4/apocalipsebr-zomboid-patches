package zombie.savefile;

import java.util.*;

/** Exact character/snapshot pairs from the operator's 2026-10-26 verification failure export.
 * Local SQLite IDs are audit labels only. No usernames or survivor names are embedded.
 */
final class ApocBRPlayerValidationTargets {
    record Target(int localId,UUID character,UUID snapshot) {}
    private static final String OCTOBER_26="""
        3,1a0c2053-a991-31f0-8726-5b6cea1d33e5,37ba4dfe-5399-418b-933e-ff102e4bef18
        20,f7191103-58dd-43ba-ab4c-32fa695d40fc,c6101e47-c2b6-4773-a944-aedf6d78d8d5
        71,b38b7a7b-e211-35c0-9098-2b8c03eac3fb,239fd7af-f6d9-4869-8d80-d557355b880e
        79,add12ce5-18f5-3359-8132-e8b115f1b463,c46c4863-1cad-4b4f-93d2-1a27f1508047
        81,f2d6aa2d-e1d8-4f18-a952-5e1e0c612c95,38e04512-7506-499d-8aa2-bc4bbb37d884
        85,e7f5d80f-72e7-3649-bea8-1bfe2cbe9af8,4178de7e-e524-4df7-b799-74e12d5f5c58
        90,4bc60b2a-a058-34a4-b66b-75a31f56090e,2e439238-ba07-4d9c-ac4a-bb8fd4af3f1f
        103,9a440395-fd17-371c-8d31-b6a71ad2e44e,cc7d1ca2-3c2a-4298-be5f-d7bf9e84d854
        110,6cfc43b6-2ceb-4f6e-80df-c0ddfb5e1c7f,762fda13-5887-4559-8dde-a8d07d62b33d
        129,862a0feb-0091-3f6f-afd6-30944dfdfa02,bd139050-34ea-4f24-95c3-d5901f147179
        146,80b7f464-fb5f-36d6-a22c-47aeeb97a47e,ef5d3e50-5dd7-45f9-8e64-924a8f5bb1b8
        170,47329f11-b82f-3d5d-8d20-15c6c1bedba7,cba4cb82-dea8-4e69-bfa4-aed27f51b52d
        176,7a54a5e4-6387-3401-9499-2400e6038c36,b071500f-327e-4923-9a8c-267bfe65edd7
        198,33cfe2b2-2abf-3e8e-9d40-b01535381162,c4587cc6-6a7f-4a4b-b64d-1e5e3ba0fce1
        239,2b3eee33-80b1-482c-b48f-88d7cb7cae10,96146fa4-a5a6-4d7d-855e-b32fc2e70b3b
        247,8427d25d-f6b6-35bf-80b8-f88bd8ee8d5a,7481b513-8d7c-42ae-b559-6396ca338506
        250,debfa39e-409f-32a0-8d0e-15cc8afad440,db811298-3ad1-4a7e-b301-58297552f6ca
        264,01fa82ae-5bc8-3fb7-81f1-579991f5f212,6533706b-0083-4551-874f-b3a3b391736b
        273,e0e63152-e2fd-48a7-bc31-332baead571c,b330b8bb-fdd9-445a-a1cb-2eb97d12a8c3
        277,ce966bbe-026f-3f31-8c5e-6b26f53b4e11,7033e6b5-a4d3-42e2-a825-58e8278f7f42
        285,1814a4c7-5824-3ccc-8d48-7f912c1cfeb5,98ee6a21-7eb9-4869-939f-c66a9b113b59
        358,4bd01b48-64cf-3de4-8590-38ca42531df5,f74a54fe-c9f2-4ed8-880a-a47579163c99
        377,d1fb4f51-253b-3cc8-82d4-da354bdb25c2,f8336ff4-e180-41e1-965e-ddbb16df66aa
        385,ea3274b6-14d5-42ea-98ea-622a2bfcba4f,918046df-ff32-43ab-b7b5-84e000431a7f
        386,0e7cb0d7-10d1-3a66-9fd1-ba64e06c6d2c,55a60401-710d-4018-9b84-0844b1502ca2
        405,53940706-b9ca-3df5-824f-d66f4b06b415,bfd50d58-eaca-4270-aac3-c789e8a3babe
        432,c8761d24-e0a4-37d4-9879-dbb215fd0365,6eda3c75-263a-4ff8-87ea-710cca59cda5
        451,704a5f68-ae2d-449c-a307-7e525d0e4639,16ae3c26-85b7-4339-a4ae-10e49a4471ce
        491,7594c74f-2fdd-4e87-8647-5a1d38ab69de,ed6b6499-b6ee-4c36-ae95-43c04ef43943
        524,c4a7b802-50e7-45ac-9ec5-7ed3ea4b4b04,c9faa4a0-6b9c-4f01-a5ab-3ed8d0261c55
        534,2952bca7-cf4e-4cc2-9bb4-f3b44e5df4e8,62946229-901d-4ad4-84a6-c7606412ea2b
        537,2f11987d-acb7-335f-b06d-d150530751eb,e8c7f78a-fe17-4556-8203-0fb6ca70eb2e
        574,676a49fa-8c58-39f3-8d57-7b9bb41e3747,b2dc7626-3247-499c-8293-369bdba5a6bd
        581,efdf71d1-9009-3942-ba99-95ce475c532c,f7846dbf-aebc-48cf-8461-6650bd7cf4a7
        632,6bb5f9d1-ff81-4743-baf6-f2d345672a56,cc959f7a-0346-4d69-9b78-b094b540ada2
        640,6a010782-8952-4ad4-9d49-1171d9a2bbeb,f8decc31-da29-417e-b44e-0b5167c27f57
        643,9f2981a1-3097-4c32-be54-d20eed80beb7,91863b03-6ec4-4596-8eb8-dca8480bcdf2
        650,23c22899-dc92-4386-9f07-f49c4599a704,68b46d31-7d94-44e3-8fa5-09f202259c0f
        652,468bb366-d69e-42ac-bc20-13c97a699e11,edd790d2-c5ba-4b94-a627-8d213cd8341d
        653,e8250ae8-d8a1-45bd-aaaa-0de29832ec4a,e50763a2-0130-4147-8a11-063196e8a291
        656,d123278f-a31f-47ab-aab5-465fefa1a1ad,574dc165-adb1-42be-8dd6-99d5f05e4ddd
        663,748e1205-df9a-41fc-b52a-5d2b5af9efd1,02523998-079e-4c47-990b-1f3b22dc1b3d
        664,c1ddabae-d2f5-48d9-80c0-a0e14176f294,17f3ec79-7313-4c87-8e49-588624a35257
        665,59f79426-6099-4023-83a8-a6ce813ce7a1,33627e23-259a-45be-ad8d-8dd18dfd701a
        666,1138b303-148b-4576-ad46-0da13f49601b,373359d7-07b3-489c-b756-a18885ad6a46
        668,ed2e7b30-78c8-4163-9268-71b7f0a77927,3edff6fd-1f0a-4a0d-870a-2707d7b95c4b
        671,6f8c38fe-2a48-4f68-b6ef-50d478c72f22,c8ee220a-3799-4a27-b82f-130be9603431
        673,a5ff86f9-5457-4886-aae0-59b0963272ec,91dbffe1-8def-4af1-b496-12258c50110e
        675,557c9804-311a-4839-811a-8b1a0d523d1c,e4461e76-860c-4699-97a0-2aa026e2fc98
        679,199dc79a-8c52-43b2-a725-94e76f86408b,b9e29408-7786-4b4f-b242-a620e4148125
        685,ba0d02a9-1b08-41f1-afc5-15a0fa86580e,98c789ee-416f-45fc-9e73-1dcdd3d5fe24
        688,68f0baad-9a16-478f-a57f-c8984b853198,0f807a88-66fd-40e2-a2ef-7269a47facd7
        690,1dbccba3-65fc-4d99-9abd-79319563f8a6,a642d631-0ebf-47e9-89ad-06f9c3a4b69e
        703,f697069e-d3e1-4584-88bf-f604c65412bc,1b145327-01f2-404f-9373-d2500cfdb382
        716,19bf0f25-e663-4f58-8f30-745177fcd160,b48395ca-89ae-475a-9e4a-fe8cf5ab4de3
        717,84c5a015-0168-4311-9990-d8052ec8ce0f,89b87fb8-6d53-42ae-8adc-7648fce8e70a
        726,96c20f94-3673-4547-9374-40d84527891a,e17f2b9e-6684-4a01-af31-64fd389e4eb7
        730,b828352e-6182-4c04-9e8f-aaee149498e1,9a20df86-7225-491b-a4ad-d5ec3f4c33a9
        731,3e911390-6827-4b8d-a8ae-4c30ed6f9006,a9413de6-84a0-4eee-b74b-d5e39fba6d8f
        732,92ffe86b-179d-475b-830a-6c24ba8fe358,05c5b6b3-a78c-4fe8-9f73-c46f78cb452e
        734,73110acd-5acf-444f-99f0-17ccc2b250f0,950f20dc-0444-4c51-9577-a33df7b37a31
        738,19f0f16e-eca1-4ebc-946c-67f135bc6eae,73937b4b-ba2c-412e-82e3-96de0a2fe95b
        745,f3b20e03-c1f8-4cb8-b1a0-e02eba94bc27,dbc5009d-d5eb-4320-9bc2-92c04180dd07
        746,49dd2f44-191e-46bb-9f8c-1f4b5081601c,a3f96993-4895-4a2c-917e-46c18653d5d9
        750,3fe1d654-dd01-42e3-ab74-962cc36e1281,91d8973b-7e9d-4295-a9db-fb7b89e396ca
        754,f4ca77a1-de63-455e-9eff-466c8e7ccfdb,4400516a-0209-4983-b564-9276ec79184e
        762,72e8f85a-7b61-48a4-a8a8-a184b4f569a6,6328fb40-2372-4d99-ac42-39e9320b384a
        775,ce60e541-8522-4f90-83e4-9bd839e7e1f4,e0b2b63c-1f6a-47ba-aa77-f35331d6ff6a
        778,b0ff2207-09f8-4684-8a1c-814106f1b06f,44c886d5-197e-45b7-beb3-8b6a21a03136
        784,ffc635cc-961a-4295-9990-f48a5dceb995,9693c673-86c7-4fd7-bda0-29a1b3bdacf1
        786,88f953a8-ee00-47d8-8a3d-cad12f6cf282,52a63c81-8233-4836-896d-4bb4709c31d6
        787,40c5cdf7-6adb-4ba6-a47a-56c605cde523,f4c69f89-8851-4b2a-9d61-cd9b914881c4
        788,705096a5-241b-4248-bf10-816b2de684e7,d8564b97-f494-4d2f-a1e5-81e5adc2a8f0
        794,ccf9757d-9b1a-4b44-ac8d-ab04be880889,8a5ada50-a2a9-4101-8a22-4656a03ac489
        811,026db393-1a2e-49b7-a3d9-1764b2d3d6f2,249591af-3300-40db-8e12-b1b08ff8e7bc
        818,c72c1e08-c66a-4b01-833b-382aeca11528,badbe364-877c-4d6a-b605-684a151e92c5
        826,14cdb4fb-0fd4-45ae-b736-0a2a3d4bb6aa,dd8a97c4-9f4d-4b82-b62d-5a9db418c076
        833,ae8f783b-1a13-4c1e-a038-80990b47dc89,bd2a0a75-7870-4ee3-9f57-f4a42cf55244
        839,5ab78703-1bff-4f04-ab23-5b040ef0fdc3,1a85b628-f877-4076-a383-665446c9a019
        845,c3cb4c93-2d4f-4263-93bb-642d0970c009,6602f161-3b3a-4d8f-b28f-1033534f6cec
        846,c9cef8fc-01d3-403d-9e31-5e2cda9b2be5,ac370ace-af01-423c-95c7-4a3b87fc8b8c
        847,8cdccb4a-0ecf-4e39-a126-23753162c21c,9e0f8b99-f0bc-4dab-8c62-b976fa82eb1d
        849,3d322c21-63b2-4d64-b07b-a9f8bbd164d4,2284a133-b934-41e4-a834-966be343651f
        856,a5f2ebb1-9623-4e41-8a4e-bfaa900f8f7f,8ae77bde-125d-428a-8bfd-0eadb72d0ef4
        """;
    static List<Target> failedOctober26() {
        List<Target> targets=new ArrayList<>();Set<UUID> seen=new HashSet<>();
        for(String line:OCTOBER_26.strip().split("\\R")) {
            String[] fields=line.strip().split(",");
            var target=new Target(Integer.parseInt(fields[0]),UUID.fromString(fields[1]),UUID.fromString(fields[2]));
            if(fields.length!=3||target.localId()<1||!seen.add(target.character()))throw new IllegalStateException("Invalid validation cohort");
            targets.add(target);
        }
        return List.copyOf(targets);
    }
    private ApocBRPlayerValidationTargets(){}
}
