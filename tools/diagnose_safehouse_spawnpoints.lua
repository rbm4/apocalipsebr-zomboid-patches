-- Paste this entire file into the CLIENT Lua debugger while connected to the
-- affected server. It runs once, prints to the client log, and changes no data.
-- It inspects the same table copy used by SafeHouse.canBeSafehouse in 42.21.0.
do
    local prefix = "[SafehouseSpawnDiagnostic] "
    local issues = 0
    local checked = 0

    local function report(path, value)
        issues = issues + 1
        print(prefix .. path .. " expected=table actual=" .. type(value)
            .. " value=" .. tostring(value))
    end

    if not isClient() then
        print(prefix .. "Run on a connected multiplayer CLIENT.")
    else
        local regions = getServerSpawnRegions()
        if type(regions) ~= "table" then
            report("regions", regions)
        else
            for regionKey, region in pairs(regions) do
                local path = "regions[" .. tostring(regionKey) .. "]"
                if type(region) ~= "table" then
                    report(path, region)
                else
                    path = path .. " name=" .. tostring(region.name)
                        .. " source=" .. tostring(region.file or region.serverfile)
                    if type(region.points) ~= "table" then
                        report(path .. ".points", region.points)
                    else
                        for profession, points in pairs(region.points) do
                            local professionPath = path .. ".points[" .. tostring(profession) .. "]"
                            if type(points) ~= "table" then
                                report(professionPath, points)
                            else
                                for pointKey, point in pairs(points) do
                                    if type(point) ~= "table" then
                                        report(professionPath .. "[" .. tostring(pointKey) .. "]", point)
                                    else
                                        checked = checked + 1
                                    end
                                end
                            end
                        end
                    end
                end
            end
        end
        print(prefix .. "Finished: table points=" .. tostring(checked)
            .. " invalid table entries=" .. tostring(issues))
    end
end
