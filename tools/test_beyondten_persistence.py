"""Actual B42 BeyondTen Lua modules with explicit game/Java transport doubles."""
import os
from pathlib import Path
from lupa import LuaRuntime

MOD = Path(os.environ.get('APOCBR_TEST_BEYONDTEN', r'C:\Users\ricar\Zomboid\Workshop\apocbr\Contents\mods\BeyondTen\42'))


def runtime(server=True):
    lua = LuaRuntime()
    lua.globals().server = server
    lua.execute('''
        clock=0; logs={}; sends={}; requests={}; responses={}; sequence=0; online=true
        function print(value) logs[#logs+1]=value end
        function getTimestampMs() return clock end
        function isServer() return server end
        function isClient() return not server end
        function instanceof(obj, name) return name=='IsoPlayer' end
        function getText(...) return 'test' end
        HaloTextHelper={addTextWithArrow=function() end, HaloTextHelper_getGoodColor=function() end}
        function HaloTextHelper.getGoodColor() return {} end
        Events=setmetatable({}, {__index=function(t,name)
            local event={handlers={}}
            event.Add=function(fn) table.insert(event.handlers,fn) end
            event.fire=function(...) for _,fn in ipairs(event.handlers) do fn(...) end end
            rawset(t,name,event); return event
        end})
        perk={getId=function() return 'Aiming' end,getParent=function() return nil end,
            getName=function() return 'Aiming' end,getXp9=function() return 90 end,
            getXp10=function() return 100 end,getTotalXpForLevel=function(self,level) return level*10 end}
        Perks={Aiming=perk,FromString=function(id) if id=='Aiming' then return perk end end}
        PerkFactory={PerkList={size=function() return 1 end,get=function() return perk end}}
        player={dead=false, cid='character-a', session='session-a', data={BeyondTen={perks={Aiming={xp=9999}}}}, rawXP=100}
        function player:isDead() return self.dead end
        function player:getUsername() return 'test' end
        function player:getIndex() return 0 end
        function player:getSteamID() return 0 end
        function player:getOnlineID() return 42 end
        function player:getModData() return self.data end
        function player:getPerkLevel() return 10 end
        function player:getEmitter() return {isPlaying=function() return true end} end
        function player:getXp() return {
            getXP=function() return self.rawXP end,
            setXPToLevel=function(_,p,level) self.rawXP=level*10 end,
            AddXP=function(_,p,amount) self.rawXP=self.rawXP+amount end
        } end
        function getOnlinePlayers() return {size=function() return online and 1 or 0 end,get=function() return player end} end
        function getNumActivePlayers() return 1 end
        function getSpecificPlayer() return player end
        function getPlayer() return player end
        function sendServerCommand(p,module,command,args)
            assert(args.characterId==nil and args.session==nil and args.character_id==nil)
            sends[#sends+1]={command=command,args=args}
        end
        function sendClientCommand(p,module,command,args)
            assert(command=='RequestSync' and next(args)==nil)
            sends[#sends+1]={command=command,args=args}
        end
        function token(value)
            local a,b=5381,52711
            for i=1,#value do local c=string.byte(value,i);a=(a*33+c)%4294967291;b=(b*65599+c)%4294967291 end
            return tostring(math.floor(a))..'-'..tostring(math.floor(b))
        end
        key=token('user:test|slot:0')
        legacy={Aiming=90}; root={players={[key]={migrated=true,perks=legacy}}}
        ModData={getOrCreate=function() return root end}
        ApocBRModEntities={
            define=function() end,
            character=function(p)
                if p.dead then return {status='IDENTITY_PENDING'} end
                return {status='OK',characterId=p.cid,session=p.session}
            end,
            submit=function(name,op)
                sequence=sequence+1
                responses[#responses+1]={id=tostring(sequence),status='OK'}
                return {accepted=true,id=tostring(sequence)}
            end,
            submitCharacter=function(name,op,p,session,rows)
                assert(server and session==p.session)
                if reject then return {accepted=false,status=reject} end
                sequence=sequence+1
                for _,row in ipairs(rows) do assert(row.values.character_id==nil) end
                local req={id=tostring(sequence),op=op,rows=rows}
                requests[#requests+1]=req
                return {accepted=true,id=req.id}
            end,
            poll=function() local batch=responses;responses={};return batch end
        }
        function pump() Events.OnTickEvenPaused.fire() end
        function reply(req,status,xp,revision,imported)
            responses[#responses+1]={id=req.id,status=status,imported=imported,
                rows={{values={character_id=player.cid,perk_id='Aiming',xp=xp},revision=revision or '7'}}}
            pump()
        end
    ''')
    for layer in ('shared', 'server' if server else 'client'):
        for path in (MOD / 'media/lua' / layer).rglob('*.lua'):
            name = path.relative_to(MOD / 'media/lua' / layer).as_posix()[:-4]
            lua.globals().module_name = name
            lua.globals().module_source = path.read_text(encoding='utf-8')
            lua.execute('local source=module_source; package.preload[module_name]=function() return assert(load(source))() end')
    lua.execute("require('BeyondTen/Server')" if server else "require('BeyondTen/Client')")
    return lua


lua = runtime()
lua.execute('''
    Events.OnServerStarted.fire();pump()
    BeyondTenServer.OnPlayerUpdate(player,1)
    state=BeyondTenServer._players[player]
    assert(not state.persistenceReady and BeyondTen.GetStoredXP(player,perk)==0)
    assert(BeyondTen.GetEffectiveLevel(player,perk)==10 and #sends==0)
    pump()
    assert(#requests==1 and requests[1].op=='loadOrCreate' and requests[1].rows[1].values.xp==90)
    BeyondTenServer.OnAddXP(player,perk,5)
    assert(#state.pendingMasteryXP==1 and legacy.Aiming==90)
    reply(requests[1],'OK',100,'7',false)
    assert(state.persistenceReady and state.canonicalXP.Aiming==105 and root.players[key].perks.Aiming==105)
    assert(root.players[key].characterId=='character-a' and #sends>0)
    clock=6000;pump()
    assert(#requests==2 and requests[2].op=='patch')
    assert(requests[2].rows[1].revision=='7' and requests[2].rows[1].values.xp==105)
    BeyondTenServer.OnAddXP(player,perk,3)
    reply(requests[2],'OK',105,'8')
    assert(state.canonicalXP.Aiming==108)
    clock=12000;pump()
    assert(#requests==3 and requests[3].rows[1].values.xp==108 and requests[3].rows[1].revision=='8')
    reply(requests[3],'OK',108,'9')
    BeyondTen.ImportXP(player,{},true)
    clock=18000;pump()
    assert(requests[4].rows[1].values.xp==0 and requests[4].rows[1].revision=='9')
    reply(requests[4],'OK',0,'10')
    -- An unsolicited mutation/query command cannot enter the database service.
    local count=#requests
    Events.OnClientCommand.fire('BeyondTen','Save',player,{character_id='other',xp=999})
    assert(#requests==count)
    Events.OnClientCommand.fire('BeyondTen','RequestSync',player,{character_id='other',xp=999})
    assert(#requests==count and state.canonicalXP.Aiming==nil)
    -- A conflict does not erase local dirty data or blindly overwrite PostgreSQL.
    BeyondTen.SetStoredXP(player,perk,25)
    clock=24000;pump();reply(requests[5],'CONFLICT',0)
    clock=30000;pump()
    assert(#requests==5 and state.canonicalXP.Aiming==25)
    assert(string.find(logs[#logs],'CONFLICT',1,true))
''')
print('PASS database precedence, queued XP, generation tracking, zero reset, controller isolation, conflict retention')

lua = runtime()
lua.execute('''
    Events.OnServerStarted.fire();pump()
    BeyondTenServer.OnPlayerUpdate(player,1);pump()
    responses={{id=requests[1].id,status='OK',imported=false,rows={
        {values={character_id=player.cid,perk_id='Aiming',xp=100},revision='7'},
        {values={character_id=player.cid,perk_id='DisabledPerk',xp=20},revision='9'},
        {values={character_id=player.cid,perk_id='OtherZero',xp=0},revision='11'}
    }}};pump()
    clock=6000;pump()
    assert(#requests==1) -- clean database initialization must not rewrite any perk
    for i=1,10 do
        clock=clock+1500
        Events.OnClientCommand.fire('BeyondTen','RequestSync',player,{xp=9999})
        pump()
    end
    assert(#requests==1) -- presentation sync is independent of persistence dirtiness
    BeyondTenServer.OnAddXP(player,perk,5)
    BeyondTenServer.OnAddXP(player,perk,-5)
    clock=clock+6000;pump()
    assert(#requests==1) -- return to the committed value cancels the patch
    BeyondTen.SetStoredXP(player,perk,110)
    clock=clock+6000;pump()
    assert(#requests==2 and requests[2].op=='patch' and #requests[2].rows==1)
    assert(requests[2].rows[1].values.perk_id=='Aiming' and requests[2].rows[1].revision=='7')
    reply(requests[2],'OK',110,'8')
    BeyondTen.ImportXP(player,{},true)
    clock=clock+6000;pump()
    assert(#requests[3].rows==2) -- only the two previously nonzero perks need reset
    local revisions={}
    for _,row in ipairs(requests[3].rows) do
        assert(row.values.xp==0 and row.values.perk_id~='OtherZero')
        revisions[row.values.perk_id]=row.revision
    end
    assert(revisions.Aiming=='8' and revisions.DisabledPerk=='9')
''')
print('PASS clean loads, repeated syncs, reverted XP and zero-valued perks produce no extra database updates')

lua = runtime()
lua.execute('''
    Events.OnServerStarted.fire();pump()
    root.players[key].characterId='previous-life'
    BeyondTenServer.OnPlayerUpdate(player,1);pump()
    assert(requests[1].rows[1].values.xp==0)
    player.dead=true
    BeyondTenServer.OnPlayerDeath(player)
    reply(requests[1],'OK',90,'1',true)
    assert(BeyondTenServer._players[player]==nil and next(root.players[key].perks)==nil)
''')
print('PASS new-life legacy isolation and stale death completion')

lua = runtime()
lua.execute('''
    Events.OnServerStarted.fire();pump()
    root.players[key].characterId='previous-life'
    function player:getSteamID() return '123' end
    BeyondTenServer.OnPlayerUpdate(player,1);pump()
    assert(requests[1].rows[1].values.xp==0)
''')
print('PASS character provenance survives migration from username to Steam alias')

lua = runtime()
lua.execute('''
    Events.OnServerStarted.fire();pump()
    root.players={}
    BeyondTenServer.OnPlayerUpdate(player,1);pump()
    assert(requests[1].rows[1].values.xp==0) -- player mirror contains 9999, never trusted
    reply(requests[1],'OK',0,'1',true)
    state=BeyondTenServer._players[player]
    BeyondTen.SetStoredXP(player,perk,11)
    online=false;clock=6000;pump()
    assert(requests[2].op=='patch' and requests[2].rows[1].values.xp==11)
    reply(requests[2],'OK',11,'2')
    clock=12000;pump() -- release clean disconnected entry
    online=true
    BeyondTenServer.OnPlayerUpdate(player,1);pump()
    assert(requests[3].op=='loadOrCreate' and not state.persistenceReady)
''')
print('PASS untrusted mirror exclusion, disconnected flush and clean entry release')

lua = runtime()
lua.execute('''
    Events.OnServerStarted.fire();pump()
    reject='UNAVAILABLE'
    BeyondTenServer.OnPlayerUpdate(player,1);pump()
    state=BeyondTenServer._players[player]
    assert(not state.persistenceReady and root.players[key].perks.Aiming==90)
    reject=nil;clock=6000;pump();pump()
    assert(#requests==1)
    player.session='replacement-session'
    reply(requests[1],'OK',500,'7')
    assert(not state.persistenceReady and state.canonicalXP.Aiming==90)
''')
print('PASS unavailable retry and session completion rejection')

lua = runtime(False)
lua.execute('''
    BeyondTenClient.InitializePlayer(player)
    assert(#sends==1)
    BeyondTenClient.OnPlayerUpdate(player);assert(#sends==1)
    clock=6000;BeyondTenClient.OnPlayerUpdate(player);assert(#sends==2)
    Events.OnServerCommand.fire('BeyondTen','Sync',{player=99,perks={Aiming=100}})
    assert(player.data.BeyondTen.perks.Aiming.xp==9999)
    Events.OnServerCommand.fire('BeyondTen','Sync',{player=42,perks={Aiming=100}})
    assert(player.data.BeyondTen.perks.Aiming.xp==100)
    clock=12000;BeyondTenClient.OnPlayerUpdate(player);assert(#sends==2)
    Events.OnServerCommand.fire('BeyondTen','SyncPerk',{player=42,perk='Aiming',xp=110})
    assert(player.data.BeyondTen.perks.Aiming.xp==110 and player.rawXP==100)
''')
print('PASS client controller/service retries and local-player-only mirror updates')
