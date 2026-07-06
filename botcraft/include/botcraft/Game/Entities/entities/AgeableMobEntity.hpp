#pragma once

#include "botcraft/Game/Entities/entities/PathfinderMobEntity.hpp"

namespace Botcraft
{
    class AgeableMobEntity : public PathfinderMobEntity
    {
    protected:
#if PROTOCOL_VERSION < 775 /* < 26.1 */
        static constexpr int metadata_count = 1;
#else
        static constexpr int metadata_count = 2;
#endif
        static const std::array<std::string, metadata_count> metadata_names;
        static constexpr int hierarchy_metadata_count = PathfinderMobEntity::metadata_count + PathfinderMobEntity::hierarchy_metadata_count;

    public:
        AgeableMobEntity();
        virtual ~AgeableMobEntity();

        virtual bool IsAgeableMob() const override;

        virtual ProtocolCraft::Json::Value Serialize() const override;

        // Metadata stuff
        virtual void SetMetadataValue(const int index, const std::any& value) override;

        bool GetDataBabyId() const;
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        bool GetAgeLocked() const;
#endif

        void SetDataBabyId(const bool data_baby_id);
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        void SetAgeLocked(const bool age_locked);
#endif

    };
}
