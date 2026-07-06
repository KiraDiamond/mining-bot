#pragma once

#include "botcraft/Game/Entities/entities/monster/zombie/ZombieEntity.hpp"
#if PROTOCOL_VERSION > 404 /* > 1.13.2 */
#include "botcraft/Game/Entities/VillagerData.hpp"
#endif

namespace Botcraft
{
    class ZombieVillagerEntity : public ZombieEntity
    {
    protected:
#if PROTOCOL_VERSION < 775 /* < 26.1 */
        static constexpr int metadata_count = 2;
#else
        static constexpr int metadata_count = 3;
#endif
        static const std::array<std::string, metadata_count> metadata_names;
        static constexpr int hierarchy_metadata_count = ZombieEntity::metadata_count + ZombieEntity::hierarchy_metadata_count;

    public:
        ZombieVillagerEntity();
        virtual ~ZombieVillagerEntity();

        // Object related stuff
        virtual std::string GetName() const override;
        virtual EntityType GetType() const override;

        // Static stuff, for easier comparison
        static std::string GetClassName();
        static EntityType GetClassType();


        virtual ProtocolCraft::Json::Value Serialize() const override;

        // Metadata stuff
        virtual void SetMetadataValue(const int index, const std::any& value) override;

        bool GetDataConvertingId() const;
#if PROTOCOL_VERSION > 404 /* > 1.13.2 */
        VillagerData GetDataVillagerData() const;
#else
        int GetDataVillagerProfessionId() const;
#endif
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        bool GetDataVillagerDataFinalized() const;
#endif

        void SetDataConvertingId(const bool data_converting_id);
#if PROTOCOL_VERSION > 404 /* > 1.13.2 */
        void SetDataVillagerData(const VillagerData& data_villager_data);
#else
        void SetDataVillagerProfessionId(const int data_villager_profession_id);
#endif
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        void SetDataVillagerDataFinalized(const bool data_villager_data_finalized);
#endif

    protected:
        virtual double GetWidthImpl() const override;
        virtual double GetHeightImpl() const override;

    };
}
